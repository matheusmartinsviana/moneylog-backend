package com.moneylog.core.sk.ledger;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

public record VoidDecision(boolean allowed, String code, String reason) {

    public VoidDecision {
        if (!allowed && (code == null || !code.matches("[A-Z][A-Z0-9_]*"))) {
            throw new InvalidPostingException("a recusa de estorno precisa de code UPPER_SNAKE_CASE.");
        }
    }

    public static VoidDecision allow() {
        return new VoidDecision(true, null, null);
    }

    public static VoidDecision refuse(String code, String reason) {
        return new VoidDecision(false, code, reason);
    }
}
