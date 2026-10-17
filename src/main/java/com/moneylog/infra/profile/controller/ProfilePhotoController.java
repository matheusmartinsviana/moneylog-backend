package com.moneylog.infra.profile.controller;

import java.io.IOException;
import java.util.List;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoInvalidImageException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoTooLargeException;
import com.moneylog.core.goal.photo.PhotoLimits;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoContentUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.GetProfilePhotoContentUseCase.ProfilePhotoContentQuery;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ProfilePhotoInfo;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.RemoveProfilePhotoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ReplaceProfilePhotoUseCase;
import com.moneylog.core.profile.app.usecase.ProfilePhotoUseCases.ReplaceProfilePhotoUseCase.ReplaceProfilePhotoCommand;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.exception.RequestValidationException.FieldIssue;
import com.moneylog.infra.goal.controller.PhotoUploadRateLimiter;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/profile/photo")
public class ProfilePhotoController {

    public record ProfilePhotoResponse(boolean hasPhoto, long photoVersion) {

        static ProfilePhotoResponse of(ProfilePhotoInfo info) {
            return new ProfilePhotoResponse(info.hasPhoto(), info.photoVersion());
        }
    }

    private final ReplaceProfilePhotoUseCase replace;
    private final GetProfilePhotoContentUseCase content;
    private final RemoveProfilePhotoUseCase remove;
    private final PhotoUploadRateLimiter rateLimiter;
    private final long maxUploadBytes;

    public ProfilePhotoController(ReplaceProfilePhotoUseCase replace, GetProfilePhotoContentUseCase content, RemoveProfilePhotoUseCase remove,
                                  PhotoUploadRateLimiter rateLimiter, PhotoLimits limits) {
        this.replace = replace;
        this.content = content;
        this.remove = remove;
        this.rateLimiter = rateLimiter;
        this.maxUploadBytes = limits.maxUploadBytes();
    }

    @PutMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ProfilePhotoResponse replace(@CurrentUser UUID userId, @RequestPart("file") MultipartFile file) throws IOException {
        rateLimiter.acquire(userId);
        if (file.isEmpty()) {
            throw new GoalPhotoInvalidImageException();
        }
        if (file.getSize() > maxUploadBytes) {
            throw new GoalPhotoTooLargeException(maxUploadBytes);
        }
        return ProfilePhotoResponse.of(replace.handle(new ReplaceProfilePhotoCommand(userId, file.getBytes())));
    }

    @GetMapping("/content")
    public ResponseEntity<byte[]> content(@CurrentUser UUID userId, @RequestParam(defaultValue = "display") String variant) {
        Variant parsed = switch (variant) {
            case "display" -> Variant.DISPLAY;
            case "thumb" -> Variant.THUMBNAIL;
            default -> throw new RequestValidationException(List.of(new FieldIssue("variant", "INVALID_FORMAT", "must be one of: thumb, display")));
        };
        return ResponseEntity.ok()
                .contentType(MediaType.IMAGE_JPEG)
                .cacheControl(CacheControl.noStore())
                .header("X-Content-Type-Options", "nosniff")
                .header("Content-Security-Policy", "default-src 'none'; sandbox")
                .body(content.handle(new ProfilePhotoContentQuery(userId, parsed)));
    }

    @DeleteMapping
    public ResponseEntity<Void> delete(@CurrentUser UUID userId) {
        remove.handle(userId);
        return ResponseEntity.noContent().build();
    }
}
