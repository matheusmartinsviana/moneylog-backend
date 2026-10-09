package com.moneylog.infra.goal.controller;

import java.io.IOException;
import java.net.URI;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;

import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.GetGoalPhotoContentUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.GetGoalPhotoContentUseCase.GoalPhotoContentQuery;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.RemoveGoalPhotoUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.RemoveGoalPhotoUseCase.RemoveGoalPhotoCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.ReorderGoalPhotosUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.ReorderGoalPhotosUseCase.ReorderGoalPhotosCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalFeaturedUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalFeaturedUseCase.SetGoalFeaturedCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalPhotoCoverUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.SetGoalPhotoCoverUseCase.SetGoalPhotoCoverCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase.UploadGoalPhotoCommand;
import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.UploadGoalPhotoUseCase.UploadedPhoto;
import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.exception.RequestValidationException.FieldIssue;
import com.moneylog.infra.goal.controller.GoalRequests.GoalFeaturedInput;
import com.moneylog.infra.goal.controller.GoalRequests.GoalPhotoOrderInput;
import com.moneylog.infra.security.CurrentUser;

/**
 * Fotos e destaque da meta (ADR-018). O envio é multipart e o conteúdo só sai por esta rota autenticada (Bearer): sem URL assinada, sem token
 * na query. A resposta do conteúdo é {@code no-store} e {@code nosniff}. O nome do arquivo enviado é ignorado e nunca vai para log; o log só
 * leva ids e tamanhos.
 */
@RestController
@RequestMapping("/v1/goals")
public class GoalPhotoController {

    private static final Logger log = LoggerFactory.getLogger(GoalPhotoController.class);

    private final UploadGoalPhotoUseCase upload;
    private final ReorderGoalPhotosUseCase reorder;
    private final SetGoalPhotoCoverUseCase cover;
    private final RemoveGoalPhotoUseCase remove;
    private final GetGoalPhotoContentUseCase content;
    private final SetGoalFeaturedUseCase featured;
    private final PhotoUploadRateLimiter rateLimiter;
    private final long maxUploadBytes;

    @SuppressWarnings("java:S107")
    public GoalPhotoController(UploadGoalPhotoUseCase upload, ReorderGoalPhotosUseCase reorder, SetGoalPhotoCoverUseCase cover,
                               RemoveGoalPhotoUseCase remove, GetGoalPhotoContentUseCase content, SetGoalFeaturedUseCase featured,
                               PhotoUploadRateLimiter rateLimiter, PhotoLimits limits) {
        this.upload = upload;
        this.reorder = reorder;
        this.cover = cover;
        this.remove = remove;
        this.content = content;
        this.featured = featured;
        this.rateLimiter = rateLimiter;
        this.maxUploadBytes = limits.maxUploadBytes();
    }

    @PostMapping(value = "/{id}/photos", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<GoalResponse.PhotoResponse> upload(@CurrentUser UUID userId, @PathVariable UUID id,
                                                             @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                             @RequestPart("file") MultipartFile file) throws IOException {
        rateLimiter.acquire(userId);
        if (file.isEmpty()) {
            throw new GoalPhotoInvalidImageException();
        }
        if (file.getSize() > maxUploadBytes) {
            throw new GoalPhotoTooLargeException(maxUploadBytes);
        }
        UploadedPhoto uploaded = upload.handle(new UploadGoalPhotoCommand(userId, id, file.getBytes(), idempotencyKey));
        log.info("Foto de meta enviada: goalId={}, photoId={}, bytes={}", id, uploaded.photo().id(), uploaded.photo().sizeBytes());
        return ResponseEntity.created(URI.create("/v1/goals/" + id + "/photos/" + uploaded.photo().id()))
                .body(GoalResponse.photo(uploaded.photo(), uploaded.position()));
    }

    /** Conteúdo JPEG autenticado. {@code variant}: {@code display} (padrão) ou {@code thumb}. */
    @GetMapping("/{id}/photos/{photoId}/content")
    public ResponseEntity<byte[]> content(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID photoId,
                                          @RequestParam(defaultValue = "display") String variant) {
        Variant parsed = switch (variant) {
            case "display" -> Variant.DISPLAY;
            case "thumb" -> Variant.THUMBNAIL;
            default -> throw new RequestValidationException(List.of(new FieldIssue("variant", "INVALID_FORMAT", "must be one of: thumb, display")));
        };
        byte[] bytes = content.handle(new GoalPhotoContentQuery(userId, id, photoId, parsed));
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(bytes);
    }

    @PutMapping("/{id}/photos/order")
    public GoalResponse reorder(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody GoalPhotoOrderInput body) {
        return GoalResponse.of(reorder.handle(new ReorderGoalPhotosCommand(userId, id, body.photoIds())));
    }

    @PutMapping("/{id}/photos/{photoId}/cover")
    public GoalResponse cover(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID photoId) {
        return GoalResponse.of(cover.handle(new SetGoalPhotoCoverCommand(userId, id, photoId)));
    }

    @DeleteMapping("/{id}/photos/{photoId}")
    public GoalResponse delete(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID photoId) {
        return GoalResponse.of(remove.handle(new RemoveGoalPhotoCommand(userId, id, photoId)));
    }

    @PutMapping("/{id}/featured")
    public GoalResponse featured(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody GoalFeaturedInput body) {
        return GoalResponse.of(featured.handle(new SetGoalFeaturedCommand(userId, id, body.featured())));
    }
}
