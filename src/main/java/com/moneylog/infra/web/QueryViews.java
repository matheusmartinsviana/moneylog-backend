package com.moneylog.infra.web;

import com.moneylog.core.finance.domain.ConsumptionView;
import com.moneylog.infra.exception.RequestValidationException;

public final class QueryViews {

    private QueryViews() {
    }

    public static ConsumptionView parse(String view) {
        if (view == null) {
            return ConsumptionView.MINE;
        }
        try {
            return ConsumptionView.valueOf(view);
        } catch (IllegalArgumentException e) {
            throw new RequestValidationException("view", "INVALID_FORMAT", "'view' deve ser MINE ou GROSS.");
        }
    }
}
