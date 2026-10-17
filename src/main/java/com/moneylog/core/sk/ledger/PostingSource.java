package com.moneylog.core.sk.ledger;

import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

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

    public static PostingSource thirdPartyReceipt(UUID receiptId) {
        return new PostingSource(PostingSourceType.THIRD_PARTY_RECEIPT, receiptId, null);
    }

    public static PostingSource thirdPartyReceiptInBalance(UUID receiptId) {
        return new PostingSource(PostingSourceType.THIRD_PARTY_RECEIPT_IN_BALANCE, receiptId, null);
    }

    public static PostingSource thirdPartyRefundAdjustment(UUID receiptId) {
        return new PostingSource(PostingSourceType.THIRD_PARTY_REFUND_ADJUSTMENT, receiptId, null);
    }

    public static PostingSource sharePayable(UUID transactionId) {
        return new PostingSource(PostingSourceType.SHARE_PAYABLE, transactionId, null);
    }

    public UUID transactionId() {
        return type == PostingSourceType.TRANSACTION ? sourceId : null;
    }
}
