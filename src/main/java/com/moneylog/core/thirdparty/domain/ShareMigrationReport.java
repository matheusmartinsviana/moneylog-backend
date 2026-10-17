package com.moneylog.core.thirdparty.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.domain.SplitMode;

public record ShareMigrationReport(String hash, int eligible, int exceptions, Money receivableRecognized, Money refundsNeutralized,
                                   List<PurchaseEntry> purchases, List<MonthEntry> months) {

    public enum Status {
        MIGRATE, EXCEPTION
    }

    public enum ExceptionReason {
        NOT_CARD_PURCHASE, TRANSACTION_NOT_ACTIVE, NO_CATEGORY, ALREADY_SHARED, NO_ACTIVE_INSTALLMENTS, MARK_EXCEEDS_CHARGED,
        MARK_NOT_REPRESENTABLE, REFUND_EXCEEDS_THIRD_PART, RECEIVED_EXCEEDS_THIRD_PART, RECEIPT_OF_OTHER_PERSON, INVARIANT_BROKEN
    }

    public record MonthEntry(Competence competence, Money consumptionBefore, Money consumptionMineAfter, Money receivablePending,
                             Money reimbursed) {
    }

    public record PurchaseEntry(UUID transactionId, UUID personId, Status status, ExceptionReason reason, SplitMode mode, Money charged,
                                Money third, Money refunded, Money legacyReceived, List<MonthEntry> months) {
    }

    public static ShareMigrationReport of(List<PurchaseEntry> purchases) {
        Money zero = Money.zero(CurrencyCode.BRL);
        Map<Competence, Money[]> byMonth = new TreeMap<>();
        Money pending = zero;
        Money neutralized = zero;
        int eligible = 0;
        for (PurchaseEntry entry : purchases) {
            if (entry.status() != Status.MIGRATE) {
                continue;
            }
            eligible++;
            neutralized = neutralized.add(entry.refunded());
            for (MonthEntry month : entry.months()) {
                Money[] acc = byMonth.computeIfAbsent(month.competence(), k -> new Money[] {zero, zero, zero, zero});
                acc[0] = acc[0].add(month.consumptionBefore());
                acc[1] = acc[1].add(month.consumptionMineAfter());
                acc[2] = acc[2].add(month.receivablePending());
                acc[3] = acc[3].add(month.reimbursed());
                pending = pending.add(month.receivablePending());
            }
        }
        List<MonthEntry> months = new ArrayList<>();
        byMonth.forEach((competence, acc) -> months.add(new MonthEntry(competence, acc[0], acc[1], acc[2], acc[3])));
        List<PurchaseEntry> frozen = List.copyOf(purchases);
        return new ShareMigrationReport(IdempotentClaim.hash(canonical(frozen, months)), eligible, frozen.size() - eligible, pending,
                neutralized, frozen, List.copyOf(months));
    }

    public String canonical() {
        return canonical(purchases, months);
    }

    private static String canonical(List<PurchaseEntry> purchases, List<MonthEntry> months) {
        StringBuilder text = new StringBuilder("share-migration-v1\n");
        for (PurchaseEntry p : purchases) {
            text.append("P|").append(p.transactionId()).append('|').append(p.personId()).append('|').append(p.status()).append('|')
                    .append(p.reason()).append('|').append(p.mode()).append('|').append(p.charged().toPlainString()).append('|')
                    .append(p.third().toPlainString()).append('|').append(p.refunded().toPlainString()).append('|')
                    .append(p.legacyReceived().toPlainString()).append('\n');
            for (MonthEntry m : p.months()) {
                appendMonth(text, "PM", m);
            }
        }
        for (MonthEntry m : months) {
            appendMonth(text, "M", m);
        }
        return text.toString();
    }

    private static void appendMonth(StringBuilder text, String tag, MonthEntry m) {
        text.append(tag).append('|').append(m.competence()).append('|').append(m.consumptionBefore().toPlainString()).append('|')
                .append(m.consumptionMineAfter().toPlainString()).append('|').append(m.receivablePending().toPlainString()).append('|')
                .append(m.reimbursed().toPlainString()).append('\n');
    }
}
