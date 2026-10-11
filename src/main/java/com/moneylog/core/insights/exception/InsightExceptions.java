package com.moneylog.core.insights.exception;

import com.moneylog.core.sk.exception.NotFoundException;

/** Exceções dos insights calculados. */
public final class InsightExceptions {

    private InsightExceptions() {
    }

    /** O insight não existe para este usuário (id de outro usuário ou que já não é calculado): 404, sem revelar qual dos dois. */
    public static class InsightNotFoundException extends NotFoundException {
        public InsightNotFoundException(String id) {
            super("INSIGHT_NOT_FOUND", "Insight não encontrado: " + id);
        }
    }
}
