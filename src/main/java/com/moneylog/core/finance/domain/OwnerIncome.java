package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.Occurrence;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

public record OwnerIncome(Money total, Money appliedToDebt, List<Item> items) {

    public enum Source {
        INCOME_PROFILE,
        RECURRENCE,
        TRANSACTION
    }

    public enum Status {
        PLANNED,
        RECEIVED
    }

    public enum Confidence {
        CONFIRMED,
        ESTIMATED
    }

    public record Item(Source source, UUID refId, LocalDate date, Money amount, Status status, Confidence confidence) {
    }

    public OwnerIncome {
        items = List.copyOf(items);
    }

    public Money receivedInAccount() {
        return total;
    }

    public Money alreadyReceived() {
        return Money.sum(items.stream().filter(i -> i.status() == Status.RECEIVED).map(Item::amount).toList(), CurrencyCode.BRL);
    }

    public static OwnerIncome compose(OwnerSources sources, OwnerIncomeInputs inputs, Competence competence, LocalDate asOf) {
        List<Item> items = new ArrayList<>();
        Set<String> seenPayments = new HashSet<>();
        Set<UUID> linkedRecurrences = new HashSet<>();
        for (OwnerIncomeInputs.Link link : inputs.links()) {
            linkedRecurrences.add(link.recurrenceId());
            if (link.competence().equals(competence)) {
                seenPayments.add(paymentKey(link.profileId(), link.competence(), link.kind().name()));
                fromLink(sources, link, asOf, items);
            }
        }
        for (OwnerIncomeInputs.Preview preview : inputs.previews()) {
            if (preview.competence().equals(competence)
                    && seenPayments.add(paymentKey(preview.profileId(), preview.competence(), preview.kind().name()))) {
                items.add(new Item(Source.INCOME_PROFILE, preview.profileId(), preview.date(), preview.amount(), Status.PLANNED,
                        confidenceOf(preview.provenance())));
            }
        }
        if (inputs.salaryCategoryId() != null) {
            salaryTransactions(sources, inputs.salaryCategoryId(), linkedRecurrences, competence, asOf, items);
            salaryOccurrences(sources, inputs.salaryCategoryId(), linkedRecurrences, competence, items);
        }
        items.sort(Comparator.comparing(Item::date).thenComparing(i -> i.amount().cents()).thenComparing(i -> String.valueOf(i.refId())));
        Money total = Money.sum(items.stream().map(Item::amount).toList(), CurrencyCode.BRL);
        return new OwnerIncome(total, inputs.loanApplied().getOrDefault(competence, Money.zero(CurrencyCode.BRL)), items);
    }

    private static void fromLink(OwnerSources sources, OwnerIncomeInputs.Link link, LocalDate asOf, List<Item> items) {
        RecurringTransaction recurrence = sources.recurrence(link.recurrenceId());
        if (recurrence == null) {
            return;
        }
        LocalDate date = recurrence.schedule().startDate();
        Transaction tx = sources.realized(recurrence.id(), date);
        if (tx != null) {
            items.add(new Item(Source.INCOME_PROFILE, link.profileId(), tx.date(), tx.amount(), statusOf(tx.date(), asOf),
                    Confidence.CONFIRMED));
        } else if (recurrence.isActive() && !recurrence.skippedDates().contains(date)) {
            items.add(new Item(Source.INCOME_PROFILE, link.profileId(), date, recurrence.amount(), Status.PLANNED,
                    confidenceOf(recurrence.provenance())));
        }
    }

    private static void salaryTransactions(OwnerSources sources, UUID salaryCategoryId, Set<UUID> linkedRecurrences, Competence competence,
                                           LocalDate asOf, List<Item> items) {
        for (Transaction tx : sources.records().transactions()) {
            UUID recurrenceId = tx.origin().recurrenceId();
            if (!tx.isActive() || tx.type() != TransactionType.INCOME || !salaryCategoryId.equals(tx.categoryId())
                    || !competence.equals(tx.effectiveIncomeCompetence())
                    || (recurrenceId != null && linkedRecurrences.contains(recurrenceId))) {
                continue;
            }
            items.add(new Item(recurrenceId == null ? Source.TRANSACTION : Source.RECURRENCE, recurrenceId == null ? tx.id() : recurrenceId,
                    tx.date(), tx.amount(), statusOf(tx.date(), asOf), Confidence.CONFIRMED));
        }
    }

    private static void salaryOccurrences(OwnerSources sources, UUID salaryCategoryId, Set<UUID> linkedRecurrences, Competence competence,
                                          List<Item> items) {
        for (RecurringTransaction recurrence : sources.recurrences()) {
            if (recurrence.type() != TransactionType.INCOME || !recurrence.isActive() || linkedRecurrences.contains(recurrence.id())
                    || !salaryCategoryId.equals(recurrence.categoryId())) {
                continue;
            }
            LocalDate until = competence.plusMonths(2).lastDay();
            for (Occurrence occurrence : recurrence.occurrences(recurrence.schedule().startDate(), until)) {
                Competence paid = Competence.from(occurrence.date()).plusMonths(recurrence.incomeCompetenceOffsetMonths());
                if (paid.equals(competence) && sources.realized(recurrence.id(), occurrence.date()) == null) {
                    items.add(new Item(Source.RECURRENCE, recurrence.id(), occurrence.date(), occurrence.amount(), Status.PLANNED,
                            confidenceOf(recurrence.provenance())));
                }
            }
        }
    }

    private static Confidence confidenceOf(Provenance provenance) {
        return provenance.estimated() ? Confidence.ESTIMATED : Confidence.CONFIRMED;
    }

    private static Status statusOf(LocalDate date, LocalDate asOf) {
        return date.isAfter(asOf) ? Status.PLANNED : Status.RECEIVED;
    }

    private static String paymentKey(UUID profileId, Competence competence, String kind) {
        return profileId + "/" + competence + "/" + kind;
    }
}
