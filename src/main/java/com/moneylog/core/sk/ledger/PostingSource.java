package com.moneylog.core.sk.ledger;

import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

/**
 * Origem de um grupo de postings (D1 §1.1, S-45): tipo + id da entidade que gerou + parcela (só em
 * {@code TRANSACTION}). É a chave usada para desativar o grupo (estorno, cancelamento de parcela, correção).
 */
public record PostingSource(PostingSourceType type, UUID sourceId, UUID installmentId) {

    public PostingSource {
        if (type == null) {
            throw new InvalidPostingException("a origem do posting é obrigatória.");
        }
        if (sourceId == null) {
            throw new InvalidPostingException("o id da origem é obrigatório.");
        }
        if (installmentId != null && type != PostingSourceType.TRANSACTION) {
            throw new InvalidPostingException("parcela só existe em postings de movimentação.");
        }
    }

    public static PostingSource transaction(UUID transactionId) {
        return new PostingSource(PostingSourceType.TRANSACTION, transactionId, null);
    }

    public static PostingSource installment(UUID transactionId, UUID installmentId) {
        if (installmentId == null) {
            throw new InvalidPostingException("o id da parcela é obrigatório.");
        }
        return new PostingSource(PostingSourceType.TRANSACTION, transactionId, installmentId);
    }

    public static PostingSource accountOpening(UUID accountId) {
        return new PostingSource(PostingSourceType.ACCOUNT_OPENING, accountId, null);
    }

    public static PostingSource debtRecognition(UUID debtId) {
        return new PostingSource(PostingSourceType.DEBT_RECOGNITION, debtId, null);
    }

    public static PostingSource debtCancellation(UUID debtId) {
        return new PostingSource(PostingSourceType.DEBT_CANCELLATION, debtId, null);
    }

    /** Id da movimentação quando a origem é {@code TRANSACTION}; {@code null} nas demais. */
    public UUID transactionId() {
        return type == PostingSourceType.TRANSACTION ? sourceId : null;
    }
}
