package com.moneylog.infra.projection.controller;

import com.moneylog.core.finance.domain.Horizon;

public enum ProjectionHorizon {
    D30(Horizon.D30),
    D90(Horizon.D90),
    M6(Horizon.M6),
    M12(Horizon.M12);

    private final Horizon horizon;

    ProjectionHorizon(Horizon horizon) {
        this.horizon = horizon;
    }

    public Horizon horizon() {
        return horizon;
    }
}
