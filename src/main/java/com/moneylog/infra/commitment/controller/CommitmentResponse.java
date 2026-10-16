package com.moneylog.infra.commitment.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.domain.CommitmentClauses;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.ScheduleItemType;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;

public record CommitmentResponse(UUID id, CommitmentKind kind, String name, String counterparty, String notes, Money total, Money paid,
                                 Money nominalBalance, String progress, Integer installmentsTotal, Integer installmentsPaid,
                                 Integer remainingInstallments, LocalDate nextDueDate, Money directPending, Money financingPending,
                                 List<MonthAmount> pendingByMonth, List<Item> schedule, long version, Instant createdAt, Instant updatedAt,
                                 Clauses clauses, List<PersonTotal> totalsByPerson) {

    public record Item(int position, String label, ScheduleItemType type, LocalDate dueDate, Money amount, boolean paid, LocalDate paidOn,
                       List<Share> shares, boolean sharesDefined, UUID coBuyerPersonId, Provenance provenance) {
    }

    public record Share(UUID personId, Money amount) {
    }

    public record Clauses(int lateFeeBps, int monthlyInterestBps, int terminationRetentionBps, Money terminationRetentionAmount, String notes) {
    }

    public record PersonTotal(UUID personId, Money paid, Money pending, Money total) {
    }

    public record MonthAmount(String competence, Money amount) {
    }

    public static CommitmentResponse of(CommitmentView view) {
        TrackedCommitment c = view.commitment();
        boolean project = c.kind() == CommitmentKind.LONG_TERM_PROJECT;
        return new CommitmentResponse(c.id(), c.kind(), c.name(), c.counterparty(), c.notes(), c.nominalTotal(), c.paid(),
                c.nominalBalance(), c.progress() == null ? null : c.progress().toPlainString(), c.installmentsTotal(), c.installmentsPaid(),
                c.remainingInstallments(), c.nextDueDate(), project ? c.directPending() : null, project ? c.financingPending() : null,
                c.pendingByMonth().entrySet().stream().map(e -> new MonthAmount(e.getKey().toString(), e.getValue())).toList(),
                c.schedule().stream().map(CommitmentResponse::item).toList(), view.stored().version(), view.stored().createdAt(),
                view.stored().updatedAt(), clauses(c), c.totalsByPerson().stream()
                        .map(t -> new PersonTotal(t.personId(), t.paid(), t.pending(), t.total())).toList());
    }

    private static Clauses clauses(TrackedCommitment c) {
        CommitmentClauses k = c.clauses();
        return k == null ? null : new Clauses(k.lateFeeBps(), k.monthlyInterestBps(), k.terminationRetentionBps(),
                c.terminationRetentionAmount(), k.notes());
    }

    private static Item item(ScheduleItem i) {
        return new Item(i.position(), i.label(), i.type(), i.dueDate(), i.amount(), i.isPaid(), i.paidOn(),
                i.shares().stream().map(s -> new Share(s.personId(), s.amount())).toList(), i.sharesDefined(), i.coBuyerPersonId(),
                i.provenance());
    }
}
