package com.moneylog.core.finance.app.migration;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.ShareSettlement;
import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionUseCase;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ShareMigrationGateway;
import com.moneylog.core.thirdparty.app.gateways.ShareMigrationGateway.MigratedMark;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.finance.app.migration.ShareMigrationPlanner.Plan;
import com.moneylog.core.finance.app.migration.ShareMigrationPlanner.RefundRef;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ShareMigrationBackupRequiredException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ShareMigrationNothingToApplyException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ShareMigrationReportMismatchException;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class ApplyShareMigrationUseCaseImpl implements ApplyShareMigrationUseCase {

    public static final String REVISION_CHANGE = "MIGRATE_THIRD_PARTY_MARK";

    private final FinancialRecordsGateway records;
    private final ThirdPartyExpenseGateway marks;
    private final ShareMigrationGateway migrations;
    private final TransactionGateway transactions;
    private final TransactionRevisionGateway revisions;
    private final LedgerWriteGateway ledger;
    private final ShareSettlement shareSettlement;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public ApplyShareMigrationUseCaseImpl(FinancialRecordsGateway records, ThirdPartyExpenseGateway marks, ShareMigrationGateway migrations,
                                          TransactionGateway transactions, TransactionRevisionGateway revisions, LedgerWriteGateway ledger,
                                          ShareSettlement shareSettlement, IdGenerator ids, TimeProvider time) {
        this.records = records;
        this.marks = marks;
        this.migrations = migrations;
        this.transactions = transactions;
        this.revisions = revisions;
        this.ledger = ledger;
        this.shareSettlement = shareSettlement;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public Result handle(ApplyShareMigrationCommand command) {
        if (!command.backupConfirmed()) {
            throw new ShareMigrationBackupRequiredException();
        }
        UUID userId = command.userId();
        List<Plan> plans = ShareMigrationPlanner.plan(records.load(userId).transactions(), marks.findAll(userId), marks.findAllReceipts(userId));
        ShareMigrationReport report = ShareMigrationPlanner.report(plans);
        List<Plan> eligible = plans.stream().filter(Plan::eligible).toList();
        if (eligible.isEmpty() && migrations.existsApplied(userId, command.reportHash())) {
            return new Result(command.reportHash(), true, 0, 0, 0);
        }
        if (!report.hash().equals(command.reportHash())) {
            throw new ShareMigrationReportMismatchException();
        }
        if (eligible.isEmpty()) {
            throw new ShareMigrationNothingToApplyException();
        }
        eligible.stream().map(p -> p.purchase().id()).sorted().forEach(id -> transactions.lockForUpdate(userId, id));
        shareSettlement.lockPersons(userId, eligible.stream().map(p -> p.mark().personId()).distinct().toList());
        List<Plan> locked = ShareMigrationPlanner.plan(records.load(userId).transactions(), marks.findAll(userId), marks.findAllReceipts(userId));
        if (!ShareMigrationPlanner.report(locked).hash().equals(report.hash())) {
            throw new ShareMigrationReportMismatchException();
        }
        eligible = locked.stream().filter(Plan::eligible).toList();
        Instant now = time.now();
        int refunds = 0;
        int legacy = 0;
        for (Plan plan : eligible) {
            apply(userId, plan, report.hash(), now);
            refunds += plan.refunds().size();
            legacy += plan.legacyReceipts().size();
        }
        return new Result(report.hash(), false, eligible.size(), refunds, legacy);
    }

    private void apply(UUID userId, Plan plan, String hash, Instant now) {
        Transaction current = transactions.findById(userId, plan.purchase().id()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(plan.purchase().id()));
        if (current.version() != plan.purchase().version() || current.purchaseShares() != null
                || !transactions.sumActiveRefundsOf(userId, current.id()).equals(plan.entry().refunded())) {
            throw new ShareMigrationReportMismatchException();
        }
        Transaction replacement = CardTransactions.reshare(current, plan.mode(), plan.lines(), Map.of());
        transactions.update(replacement);
        revisions.save(userId, current, Math.toIntExact(current.version()) + 1, now, REVISION_CHANGE,
                "migração da marca de terceiro para o rateio, relatório " + hash);
        ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, current.id(), UpdateTransactionUseCase.EDIT_REASON, now);
        ledger.append(userId, replacement.entries().stream().filter(LedgerEntry::active).toList());
        for (RefundRef refund : plan.refunds()) {
            ThirdPartyReceipt receipt = ThirdPartyReceipt.migratedRefund(ids.next(), userId, plan.mark().personId(), current.id(),
                    refund.amount(), refund.date(), "migração: reembolso " + refund.id());
            marks.insertReceipt(receipt);
            ledger.append(userId, receipt.refundNeutralizationEntries(refund.categoryId()));
        }
        List<ThirdPartyReceipt> ledgered = new ArrayList<>();
        for (ThirdPartyReceipt receipt : plan.legacyReceipts()) {
            LocalDate on = receipt.receivedOn() == null ? time.today() : receipt.receivedOn();
            marks.ledgerReceipt(userId, receipt.id(), on);
            ledgered.add(receipt.ledgeredOn(on));
        }
        for (ThirdPartyReceipt receipt : ledgered) {
            ledger.append(userId, receipt.ledgerEntries());
        }
        migrations.archive(new MigratedMark(ids.next(), plan.mark(), plan.mode(), plan.entry().third(), plan.entry().refunded(),
                plan.entry().legacyReceived(), hash, now));
        marks.delete(userId, plan.mark().id());
    }
}
