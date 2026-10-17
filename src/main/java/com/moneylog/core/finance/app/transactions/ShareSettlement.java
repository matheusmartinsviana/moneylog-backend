package com.moneylog.core.finance.app.transactions;

import java.time.Instant;
import java.time.LocalDate;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.sk.ledger.LedgerReadGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.PersonAmount;
import com.moneylog.core.transaction.domain.SharePayables;
import com.moneylog.core.transaction.domain.Transaction;

public class ShareSettlement {

    public static final String REVISION_VOID = "VOID_SHARE_PAYABLE";

    public record Plan(List<LedgerEntry> entries, List<PersonAmount> excess) {

        public static final Plan NONE = new Plan(List.of(), List.of());

        public boolean isEmpty() {
            return entries.isEmpty();
        }

        public String describe(String reason) {
            return isEmpty() ? reason : reason + " | " + SharePayables.note(excess);
        }
    }

    private final PersonGateway persons;
    private final ThirdPartyExpenseGateway receipts;
    private final LedgerReadGateway ledgerRead;
    private final LedgerWriteGateway ledgerWrite;
    private final TransactionRevisionGateway revisions;
    private final TransactionGateway transactions;

    public ShareSettlement(PersonGateway persons, ThirdPartyExpenseGateway receipts, LedgerReadGateway ledgerRead,
                           LedgerWriteGateway ledgerWrite, TransactionRevisionGateway revisions, TransactionGateway transactions) {
        this.transactions = transactions;
        this.persons = persons;
        this.receipts = receipts;
        this.ledgerRead = ledgerRead;
        this.ledgerWrite = ledgerWrite;
        this.revisions = revisions;
    }

    public void lockPersons(UUID userId, Collection<UUID> personIds) {
        personIds.stream().distinct().sorted().forEach(personId -> persons.lockForUpdate(userId, personId));
    }

    public Map<UUID, Money> receivedOn(UUID userId, UUID transactionId) {
        Map<UUID, Money> received = new TreeMap<>();
        for (ThirdPartyReceipt receipt : receipts.findAllReceipts(userId)) {
            if (receipt.ledgered() && transactionId.equals(receipt.transactionId())) {
                received.merge(receipt.personId(), receipt.amount(), Money::add);
            }
        }
        return received;
    }

    public Map<UUID, Money> migratedRefundsOn(UUID userId, UUID transactionId) {
        Map<UUID, Money> migrated = new TreeMap<>();
        for (ThirdPartyReceipt receipt : receipts.findAllReceipts(userId)) {
            if (receipt.ledgered() && transactionId.equals(receipt.transactionId())
                    && !ledgerRead.entriesOfSource(userId, PostingSourceType.THIRD_PARTY_REFUND_ADJUSTMENT, receipt.id()).isEmpty()) {
                migrated.merge(receipt.personId(), receipt.amount(), Money::add);
            }
        }
        return migrated;
    }

    public Map<UUID, Money> owedNet(UUID userId, Transaction transaction) {
        return SharePayables.owedNet(transaction, transactions.findActiveRefundsOf(userId, transaction.id()));
    }

    public Map<UUID, Money> convertedOn(UUID userId, UUID transactionId) {
        return SharePayables.alreadyConverted(transactionId, ledgerRead.entriesOfSource(userId, PostingSourceType.SHARE_PAYABLE, transactionId));
    }

    public boolean hasConversion(UUID userId, UUID transactionId) {
        return !convertedOn(userId, transactionId).isEmpty();
    }

    public Plan planConversion(UUID userId, Transaction transaction, Map<UUID, Money> owedAfter, LocalDate on) {
        if (transaction.purchaseShares() == null) {
            return Plan.NONE;
        }
        Map<UUID, Money> received = receivedOn(userId, transaction.id());
        if (received.isEmpty()) {
            return Plan.NONE;
        }
        Map<UUID, Money> converted = SharePayables.alreadyConverted(transaction.id(), ledgerRead
                .entriesOfSource(userId, PostingSourceType.SHARE_PAYABLE, transaction.id()));
        List<PersonAmount> excess = SharePayables.excess(received, owedAfter, converted);
        return excess.isEmpty() ? Plan.NONE : new Plan(SharePayables.entries(transaction.id(), excess, on), excess);
    }

    public void apply(UUID userId, Plan plan) {
        if (!plan.isEmpty()) {
            ledgerWrite.append(userId, plan.entries());
        }
    }

    public void audit(UUID userId, Transaction previous, Instant at, String change, String reason) {
        revisions.save(userId, previous, Math.toIntExact(previous.version()) + 1, at, change, reason);
    }
}
