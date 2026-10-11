package com.moneylog.core.sk.ledger;

import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.Money;

public final class LedgerExceptions {

    private LedgerExceptions() {
    }

    public static class UnbalancedPostingsException extends DomainException {
        public UnbalancedPostingsException(Money sum) {
            super("LEDGER_UNBALANCED", "A soma dos lançamentos deveria ser zero, mas é " + sum + ".");
        }
    }

    public static class InvalidPostingException extends DomainException {
        public InvalidPostingException(String reason) {
            super("LEDGER_INVALID_POSTING", "Lançamento inválido: " + reason);
        }
    }
}
