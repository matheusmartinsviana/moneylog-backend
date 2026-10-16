package com.moneylog.infra.recurrence.gateways;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.List;

import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.domain.Schedule;
import com.moneylog.core.recurrence.domain.SubscriptionTraits;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.infra.recurrence.persistence.RecurrenceEntity;
import com.moneylog.infra.recurrence.persistence.RecurrenceShareEntity;

public final class RecurrenceRepositoryMapper {

    private RecurrenceRepositoryMapper() {
    }

    public static RecurringTransaction toDomain(RecurrenceEntity e, Collection<LocalDate> realized, Collection<LocalDate> skipped) {
        return toDomain(e, realized, skipped, List.of());
    }

    public static RecurringTransaction toDomain(RecurrenceEntity e, Collection<LocalDate> realized, Collection<LocalDate> skipped,
                                                List<RecurrenceShareEntity> shareRows) {
        Schedule schedule = new Schedule(e.getFrequency(), e.getIntervalCount(), e.getIntervalUnit(), e.getStartDate(), e.getEndDate(),
                e.getMaxOccurrences());
        RecurringTransaction recurrence = RecurringTransaction.restore(e.getId(), e.getUserId(), e.getType(),
                Money.ofCents(e.getAmountCents(), CurrencyCode.of(e.getCurrency())), e.getDescription(), e.getCategoryId(),
                e.getAccountId(), e.getCardId(), e.getPaymentMethod(), schedule, realized, skipped, e.isActive(),
                e.getIncomeCompetenceOffsetMonths(), e.getNature(), purchaseShares(shareRows));
        recurrence.defineSubscriptionTraits(new SubscriptionTraits(e.isSubscription(), e.getScope(), e.getChannel(),
                e.getOverlapGroup(), e.getLastUsedOn()));
        recurrence.defineProvenance(e.provenance());
        if (e.getOriginalAmountCents() != null) {
            recurrence.defineForeignBasis(new ForeignCharge(
                    Money.ofCents(e.getOriginalAmountCents(), CurrencyCode.of(e.getOriginalCurrency())), e.getReferenceFxRate(),
                    e.getIofBps()));
        }
        return recurrence;
    }

    public static void copyForeignAndSubscription(RecurringTransaction r, RecurrenceEntity entity) {
        ForeignCharge basis = r.foreignBasis();
        SubscriptionTraits traits = r.traits();
        entity.applyForeignAndSubscription(basis == null ? null : basis.original().cents(),
                basis == null ? null : basis.original().currency().value(), basis == null ? null : basis.fxRate(),
                basis == null ? null : basis.iofBps(), traits.subscription(), traits.scope(), traits.channel(),
                traits.overlapGroup(), traits.lastUsedOn());
        entity.applyProvenance(r.provenance());
    }

    public static Versioned<RecurringTransaction> toVersioned(RecurrenceEntity e, Collection<LocalDate> realized,
                                                              Collection<LocalDate> skipped) {
        return toVersioned(e, realized, skipped, List.of());
    }

    public static Versioned<RecurringTransaction> toVersioned(RecurrenceEntity e, Collection<LocalDate> realized,
                                                              Collection<LocalDate> skipped, List<RecurrenceShareEntity> shareRows) {
        return new Versioned<>(toDomain(e, realized, skipped, shareRows), e.getVersion(), e.getCreatedAt(), e.getUpdatedAt());
    }

    private static PurchaseShares purchaseShares(List<RecurrenceShareEntity> rows) {
        if (rows.isEmpty()) {
            return null;
        }
        List<RecurrenceShareEntity> ordered = rows.stream().sorted(Comparator.comparingInt(RecurrenceShareEntity::getPosition)).toList();
        List<ShareLine> lines = ordered.stream().map(r -> new ShareLine(r.getPersonId(), r.getBps(),
                r.getAmountCents() == null ? null : Money.ofCents(r.getAmountCents(), CurrencyCode.BRL), r.provenance())).toList();
        return PurchaseShares.restore(ordered.get(0).getSplitMode(), lines);
    }

    public static List<RecurrenceShareEntity> shareRows(RecurringTransaction r, IdGenerator ids, Instant now) {
        PurchaseShares shares = r.purchaseShares();
        if (shares == null) {
            return List.of();
        }
        List<RecurrenceShareEntity> rows = new ArrayList<>();
        int position = 1;
        for (ShareLine line : shares.lines()) {
            RecurrenceShareEntity row = new RecurrenceShareEntity(ids.next(), r.userId(), r.id(), position++, shares.mode(), line.personId(),
                    line.bps(), line.amount() == null ? null : line.amount().cents(), now);
            row.applyProvenance(line.provenance());
            rows.add(row);
        }
        return rows;
    }

    public static RecurrenceEntity toEntity(RecurringTransaction r, java.time.Instant now) {
        Schedule s = r.schedule();
        RecurrenceEntity entity = new RecurrenceEntity(r.id(), r.userId(), r.type(), r.amount().cents(), r.amount().currency().value(), r.description(),
                r.categoryId(), r.accountId(), r.cardId(), r.paymentMethod(), s.frequency(), s.interval(), s.unit(), s.startDate(),
                s.endDate(), s.maxOccurrences(), r.isActive(), r.incomeCompetenceOffsetMonths(), r.nature(), now);
        copyForeignAndSubscription(r, entity);
        return entity;
    }
}
