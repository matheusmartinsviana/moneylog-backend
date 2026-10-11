package com.moneylog.core.finance.app.projection;

import java.util.UUID;

import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.domain.Projection;

public interface GetProjectionUseCase {

    Projection handle(GetProjectionQuery query);

    record GetProjectionQuery(UUID userId, Horizon horizon, boolean includeTrend) {
    }
}
