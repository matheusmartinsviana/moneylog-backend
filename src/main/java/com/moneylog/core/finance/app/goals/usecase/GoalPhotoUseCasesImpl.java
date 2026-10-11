package com.moneylog.core.finance.app.goals.usecase;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.util.UUID;
import java.util.function.Consumer;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.finance.app.goals.GoalReadModel;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.RemoveGoalPhotoUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.ReorderGoalPhotosUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalPhotoCoverUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.app.gateways.GoalGateway.PhotoUsage;
import com.moneylog.core.goal.app.gateways.PhotoStorage;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.goal.domain.GoalPhoto;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoQuotaExceededException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.photo.PhotoImagePipeline;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.goal.photo.ProcessedPhoto;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class GoalPhotoUseCasesImpl implements UploadGoalPhotoUseCase, ReorderGoalPhotosUseCase, SetGoalPhotoCoverUseCase, RemoveGoalPhotoUseCase {

    public static final String IDEMPOTENCY_SCOPE = "POST /v1/goals/{id}/photos";

    private final GoalGateway goals;
    private final PhotoStorage storage;
    private final PhotoImagePipeline pipeline;
    private final PhotoLimits limits;
    private final FinancialRecordsGateway records;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final GoalReadModel model;

    @SuppressWarnings("java:S107")
    public GoalPhotoUseCasesImpl(GoalGateway goals, PhotoStorage storage, PhotoImagePipeline pipeline, PhotoLimits limits,
                                 FinancialRecordsGateway records, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time,
                                 GoalReadModel model) {
        this.goals = goals;
        this.storage = storage;
        this.pipeline = pipeline;
        this.limits = limits;
        this.records = records;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
        this.model = model;
    }

    @Override
    public UploadedPhoto handle(UploadGoalPhotoCommand c) {
        UUID userId = c.userId();
        if (c.content() == null || c.content().length == 0) {
            throw new GoalPhotoInvalidImageException();
        }
        if (c.content().length > limits.maxUploadBytes()) {
            throw new GoalPhotoTooLargeException(limits.maxUploadBytes());
        }
        UUID photoId = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = IdempotentClaim.hash("goalId=" + c.goalId() + ";sha256=" + sha256(c.content()) + ";");
            Claim existing = IdempotentClaim.claimOrFind(idempotency, userId, IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, photoId, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                Goal goal = goals.findById(userId, c.goalId()).map(Versioned::value).orElseThrow(() -> new GoalNotFoundException(c.goalId()));
                return uploaded(goal, existing.resourceId());
            }
        }
        goals.lockForUpdate(userId, c.goalId());
        Goal goal = goals.findById(userId, c.goalId()).map(Versioned::value).orElseThrow(() -> new GoalNotFoundException(c.goalId()));
        goals.lockUserMedia(userId);
        goal.ensureCanAddPhoto(limits.maxPerGoal());
        PhotoUsage usage = goals.photoUsage(userId);
        if (usage.count() >= limits.maxPerUser()) {
            throw new GoalPhotoQuotaExceededException();
        }
        ProcessedPhoto processed = pipeline.process(c.content());
        if (usage.bytes() + processed.sizeBytes() > limits.maxBytesPerUser()) {
            throw new GoalPhotoQuotaExceededException();
        }
        GoalPhoto photo = new GoalPhoto(photoId, processed.width(), processed.height(), processed.sizeBytes(), time.now());
        goal.addPhoto(photo, limits.maxPerGoal());
        goals.save(goal);
        storage.put(userId, goal.id(), photoId, processed.display(), processed.thumbnail());
        return uploaded(goal, photoId);
    }

    private static UploadedPhoto uploaded(Goal goal, UUID photoId) {
        var photos = goal.photos();
        for (int i = 0; i < photos.size(); i++) {
            if (photos.get(i).id().equals(photoId)) {
                return new UploadedPhoto(photos.get(i), i);
            }
        }
        throw new GoalPhotoNotFoundException(photoId);
    }

    @Override
    public GoalDetails handle(ReorderGoalPhotosCommand c) {
        return change(c.userId(), c.goalId(), goal -> goal.reorderPhotos(c.photoIds()));
    }

    @Override
    public GoalDetails handle(SetGoalPhotoCoverCommand c) {
        return change(c.userId(), c.goalId(), goal -> goal.setCover(c.photoId()));
    }

    @Override
    public GoalDetails handle(RemoveGoalPhotoCommand c) {
        GoalDetails details = change(c.userId(), c.goalId(), goal -> goal.removePhoto(c.photoId()));
        storage.delete(c.userId(), c.photoId());
        return details;
    }

    private GoalDetails change(UUID userId, UUID goalId, Consumer<Goal> mutation) {
        goals.lockForUpdate(userId, goalId);
        Goal goal = goals.findById(userId, goalId).map(Versioned::value).orElseThrow(() -> new GoalNotFoundException(goalId));
        mutation.accept(goal);
        Versioned<Goal> saved = goals.save(goal);
        return model.view(userId, saved, records.load(userId));
    }

    private static String sha256(byte[] content) {
        try {
            return java.util.HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(content));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
