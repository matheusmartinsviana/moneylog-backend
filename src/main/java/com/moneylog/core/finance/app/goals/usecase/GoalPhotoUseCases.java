package com.moneylog.core.finance.app.goals.usecase;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.goal.app.gateways.PhotoStorage.Variant;
import com.moneylog.core.goal.domain.GoalPhoto;

public interface GoalPhotoUseCases {

    interface UploadGoalPhotoUseCase {
        UploadedPhoto handle(UploadGoalPhotoCommand command);

        record UploadGoalPhotoCommand(UUID userId, UUID goalId, byte[] content, UUID idempotencyKey) {
            @Override
            public String toString() {
                return "UploadGoalPhotoCommand[goalId=" + goalId + ", bytes=" + (content == null ? 0 : content.length) + "]";
            }
        }

        record UploadedPhoto(GoalPhoto photo, int position) {
        }
    }

    interface ReorderGoalPhotosUseCase {
        GoalDetails handle(ReorderGoalPhotosCommand command);

        record ReorderGoalPhotosCommand(UUID userId, UUID goalId, List<UUID> photoIds) {
        }
    }

    interface SetGoalPhotoCoverUseCase {
        GoalDetails handle(SetGoalPhotoCoverCommand command);

        record SetGoalPhotoCoverCommand(UUID userId, UUID goalId, UUID photoId) {
        }
    }

    interface RemoveGoalPhotoUseCase {
        GoalDetails handle(RemoveGoalPhotoCommand command);

        record RemoveGoalPhotoCommand(UUID userId, UUID goalId, UUID photoId) {
        }
    }

    interface GetGoalPhotoContentUseCase {
        byte[] handle(GoalPhotoContentQuery query);

        record GoalPhotoContentQuery(UUID userId, UUID goalId, UUID photoId, Variant variant) {
        }
    }

    interface SetGoalFeaturedUseCase {
        GoalDetails handle(SetGoalFeaturedCommand command);

        record SetGoalFeaturedCommand(UUID userId, UUID goalId, boolean featured) {
        }
    }
}
