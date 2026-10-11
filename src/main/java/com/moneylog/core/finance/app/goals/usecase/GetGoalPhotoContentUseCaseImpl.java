package com.moneylog.core.finance.app.goals.usecase;

import com.moneylog.core.finance.app.goals.usecase.GoalPhotoUseCases.GetGoalPhotoContentUseCase;
import com.moneylog.core.goal.app.gateways.PhotoStorage;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoNotFoundException;

public class GetGoalPhotoContentUseCaseImpl implements GetGoalPhotoContentUseCase {

    private final PhotoStorage storage;

    public GetGoalPhotoContentUseCaseImpl(PhotoStorage storage) {
        this.storage = storage;
    }

    @Override
    public byte[] handle(GoalPhotoContentQuery query) {
        return storage.get(query.userId(), query.goalId(), query.photoId(), query.variant())
                .orElseThrow(() -> new GoalPhotoNotFoundException(query.photoId()));
    }
}
