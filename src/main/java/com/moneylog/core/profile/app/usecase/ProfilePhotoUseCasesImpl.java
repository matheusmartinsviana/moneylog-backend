package com.moneylog.core.profile.app.usecase;

import java.util.UUID;

import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoQuotaExceededException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.photo.PhotoImagePipeline;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.goal.photo.ProcessedPhoto;
import com.moneylog.core.profile.app.gateways.ProfilePhotoGateway;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ProfilePhotoInfo;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoContentUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoInfoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.RemoveProfilePhotoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ReplaceProfilePhotoUseCase;
import com.moneylog.core.profile.exception.ProfileExceptions.ProfilePhotoNotFoundException;
import com.moneylog.core.sk.time.TimeProvider;

public class ProfilePhotoUseCasesImpl implements ReplaceProfilePhotoUseCase, GetProfilePhotoContentUseCase, RemoveProfilePhotoUseCase,
        GetProfilePhotoInfoUseCase {

    public static final long MAX_STORED_BYTES = 2L * 1024 * 1024;

    private final ProfilePhotoGateway photos;
    private final PhotoImagePipeline pipeline;
    private final PhotoLimits limits;
    private final TimeProvider time;

    public ProfilePhotoUseCasesImpl(ProfilePhotoGateway photos, PhotoImagePipeline pipeline, PhotoLimits limits, TimeProvider time) {
        this.photos = photos;
        this.pipeline = pipeline;
        this.limits = limits;
        this.time = time;
    }

    @Override
    public ProfilePhotoInfo handle(ReplaceProfilePhotoCommand c) {
        if (c.content() == null || c.content().length == 0) {
            throw new GoalPhotoInvalidImageException();
        }
        if (c.content().length > limits.maxUploadBytes()) {
            throw new GoalPhotoTooLargeException(limits.maxUploadBytes());
        }
        ProcessedPhoto processed = pipeline.process(c.content());
        if (processed.sizeBytes() > MAX_STORED_BYTES) {
            throw new GoalPhotoQuotaExceededException();
        }
        photos.replace(c.userId(), processed, time.now());
        return info(c.userId());
    }

    @Override
    public byte[] handle(ProfilePhotoContentQuery query) {
        return photos.content(query.userId(), query.variant()).orElseThrow(ProfilePhotoNotFoundException::new);
    }

    @Override
    public void handle(UUID userId) {
        photos.delete(userId);
    }

    @Override
    public ProfilePhotoInfo info(UUID userId) {
        return photos.updatedAt(userId).map(at -> new ProfilePhotoInfo(true, at.toEpochMilli())).orElse(ProfilePhotoInfo.NONE);
    }
}
