package com.moneylog.core.thirdparty.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseInvalidException;

public record ThirdPartyReceipt(UUID id, UUID userId, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note,
                                UUID accountId, ReceiptSettlement settlement, boolean ledgered) {

    public static final int NOTE_MAX_LENGTH = 200;

    public ThirdPartyReceipt {
        Objects.requireNonNull(id, "id");
        Objects.requireNonNull(userId, "userId");
        Objects.requireNonNull(personId, "personId");
        if (amount == null || !amount.isPositive()) {
            throw new ThirdPartyExpenseInvalidException("o valor recebido precisa ser maior que zero.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        if (note != null && note.length() > NOTE_MAX_LENGTH) {
            throw new ThirdPartyExpenseInvalidException("a nota tem até 200 caracteres.");
        }
        settlement = settlement == null ? ReceiptSettlement.ALREADY_IN_BALANCE : settlement;
        if (settlement == ReceiptSettlement.ACCOUNT && (accountId == null || !ledgered)) {
            throw new ThirdPartyExpenseInvalidException("o recebimento em conta exige a conta e o lançamento no razão.");
        }
        if (settlement == ReceiptSettlement.ALREADY_IN_BALANCE && accountId != null) {
            throw new ThirdPartyExpenseInvalidException("o recebimento já no saldo não move conta.");
        }
        if (ledgered && (transactionId == null || receivedOn == null)) {
            throw new ThirdPartyExpenseInvalidException("o recebimento lançado no razão exige a compra e a data.");
        }
    }

    public ThirdPartyReceipt(UUID id, UUID userId, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn, String note) {
        this(id, userId, personId, transactionId, amount, receivedOn, note, null, ReceiptSettlement.ALREADY_IN_BALANCE, false);
    }

    public static ThirdPartyReceipt onShare(UUID id, UUID userId, UUID personId, UUID transactionId, Money amount, LocalDate receivedOn,
                                            String note, UUID accountId) {
        return new ThirdPartyReceipt(id, userId, personId, transactionId, amount, receivedOn, note, accountId,
                accountId == null ? ReceiptSettlement.ALREADY_IN_BALANCE : ReceiptSettlement.ACCOUNT, true);
    }

    public static ThirdPartyReceipt migratedRefund(UUID id, UUID userId, UUID personId, UUID transactionId, Money amount,
                                                   LocalDate refundDate, String note) {
        return new ThirdPartyReceipt(id, userId, personId, transactionId, amount, refundDate, note, null,
                ReceiptSettlement.ALREADY_IN_BALANCE, true);
    }

    public ThirdPartyReceipt ledgeredOn(LocalDate on) {
        return new ThirdPartyReceipt(id, userId, personId, transactionId, amount, on, note, accountId, settlement, true);
    }

    public List<LedgerEntry> refundNeutralizationEntries(UUID categoryId) {
        Objects.requireNonNull(categoryId, "categoryId");
        Posting receivable = new Posting(BookRef.personReceivable(personId), amount.negate(), receivedOn);
        Posting expense = new Posting(BookRef.expense(categoryId), amount, receivedOn);
        return LedgerEntry.activeGroup(PostingSource.thirdPartyRefundAdjustment(id), List.of(expense, receivable));
    }

    public List<LedgerEntry> ledgerEntries() {
        if (!ledgered) {
            return List.of();
        }
        Posting receivable = new Posting(BookRef.personReceivable(personId), amount.negate(), receivedOn);
        if (settlement == ReceiptSettlement.ACCOUNT) {
            return LedgerEntry.activeGroup(PostingSource.thirdPartyReceipt(id),
                    List.of(new Posting(BookRef.account(accountId), amount, receivedOn), receivable));
        }
        return LedgerEntry.activeGroup(PostingSource.thirdPartyReceiptInBalance(id),
                List.of(new Posting(BookRef.equity(), amount, receivedOn), receivable));
    }

    public ReceiptLedgerEffect ledgerEffect() {
        if (!ledgered) {
            return ReceiptLedgerEffect.NONE;
        }
        return settlement == ReceiptSettlement.ACCOUNT ? ReceiptLedgerEffect.ACCOUNT_MOVEMENT : ReceiptLedgerEffect.EQUITY_OFFSET;
    }

    public PostingSourceType ledgerSourceType() {
        return settlement == ReceiptSettlement.ACCOUNT ? PostingSourceType.THIRD_PARTY_RECEIPT : PostingSourceType.THIRD_PARTY_RECEIPT_IN_BALANCE;
    }
}
