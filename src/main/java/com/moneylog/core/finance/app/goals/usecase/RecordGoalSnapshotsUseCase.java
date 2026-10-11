package com.moneylog.core.finance.app.goals.usecase;

import java.util.UUID;

public interface RecordGoalSnapshotsUseCase {

    int handle(UUID userId);
}
