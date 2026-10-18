package com.moneylog.core.profile.app.usecase;

import java.util.Arrays;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;

public interface ProfilePhotoUseCases {

    record ProfilePhotoInfo(boolean hasPhoto, long photoVersion) {

        public static final ProfilePhotoInfo NONE = new ProfilePhotoInfo(false, 0);
    }

    interface ReplaceProfilePhotoUseCase {

        record ReplaceProfilePhotoCommand(UUID userId, byte[] content) {
            @Override
            public boolean equals(Object other) {
                return other instanceof ReplaceProfilePhotoCommand that && Objects.equals(userId, that.userId) && Arrays.equals(content, that.content);
            }

            @Override
            public int hashCode() {
                return Objects.hash(userId, Arrays.hashCode(content));
            }

            @Override
            public String toString() {
                return "ReplaceProfilePhotoCommand[bytes=" + (content == null ? 0 : content.length) + "]";
            }
        }

        ProfilePhotoInfo handle(ReplaceProfilePhotoCommand command);
    }

    interface GetProfilePhotoContentUseCase {

        record ProfilePhotoContentQuery(UUID userId, Variant variant) {
        }

        byte[] handle(ProfilePhotoContentQuery query);
    }

    interface RemoveProfilePhotoUseCase {

        void handle(UUID userId);
    }

    interface GetProfilePhotoInfoUseCase {

        ProfilePhotoInfo info(UUID userId);
    }
}
