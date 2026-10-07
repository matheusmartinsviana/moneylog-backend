package com.moneylog.core.sk.ledger;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

/**
 * Resposta de uma guarda de estorno (D1 §2.4, regra 1, S-36): permitido, ou recusado com um {@code code} estável
 * (ex.: {@code TRANSACTION_IN_CLOSED_INVOICE}, {@code DEBT_HAS_SETTLEMENTS}, {@code DEBT_CLOSED}). Fica no shared
 * kernel para que cartão e dívida expressem as regras sem depender do pacote de movimentação.
 */
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
