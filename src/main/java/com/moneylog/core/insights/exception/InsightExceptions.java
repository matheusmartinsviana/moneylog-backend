package com.moneylog.core.insights.exception;

import com.moneylog.core.sk.exception.NotFoundException;

public final class InsightExceptions {

    private InsightExceptions() {
    }

    public static class InsightNotFoundException extends NotFoundException {
        public InsightNotFoundException(String id) {
            super("INSIGHT_NOT_FOUND", "Insight não encontrado: " + id);
        }
    }
}
