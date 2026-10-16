package com.moneylog.core.commitment.domain;

import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.sk.money.Money;

public record CommitmentClauses(int lateFeeBps, int monthlyInterestBps, int terminationRetentionBps, String notes) {

    public static final int FULL_BPS = 10_000;
    public static final int NOTES_MAX_LENGTH = 1000;

    public CommitmentClauses {
        requireBps(lateFeeBps, "multa por atraso");
        requireBps(monthlyInterestBps, "juros de mora");
        requireBps(terminationRetentionBps, "retenção na rescisão");
        if (notes != null && notes.length() > NOTES_MAX_LENGTH) {
            throw new InvalidCommitmentException("as observações das cláusulas têm até " + NOTES_MAX_LENGTH + " caracteres.");
        }
        notes = notes == null || notes.isBlank() ? null : notes.strip();
    }

    private static void requireBps(int value, String field) {
        if (value < 0 || value > FULL_BPS) {
            throw new InvalidCommitmentException(field + " em pontos-base fica entre 0 e " + FULL_BPS + ".");
        }
    }

    public Money terminationRetentionOver(Money total) {
        if (total == null) {
            return null;
        }
        return Money.ofCents(Math.multiplyExact(total.cents(), (long) terminationRetentionBps) / FULL_BPS, total.currency());
    }
}
