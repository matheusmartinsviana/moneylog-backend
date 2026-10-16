package com.moneylog.infra.purchasegroup.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.purchasegroup.app.PurchaseCommitments;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.sk.money.Money;

public record PurchaseGroupResponse(UUID id, String name, boolean interest, PurchaseGroupView.Status status, Money total, Money remaining,
                                    Money myRemaining, Money installmentAmountPerMonth, LocalDate lastDueDate, Divergence divergence,
                                    List<Member> members, long version, Instant createdAt, Instant updatedAt) {

    public record Divergence(Money declared, Money computed, Money difference) {
    }

    public record CardRef(UUID id, String name) {
    }

    public record Member(UUID transactionId, CardRef card, String description, int totalInstallments, int remainingInstallments,
                         Money installmentAmount, Money total, Money remaining, Money myRemaining, LocalDate lastDueDate) {
    }

    public record CommitmentMonth(String competence, Money amount, Money mine) {
    }

    public record Commitments(String until, Money committed, Money committedMine, List<CommitmentMonth> byMonth) {

        public static Commitments of(PurchaseCommitments c) {
            return new Commitments(c.until().toString(), c.committed(), c.committedMine(),
                    c.byMonth().stream().map(m -> new CommitmentMonth(m.competence().toString(), m.amount(), m.mine())).toList());
        }
    }

    public static PurchaseGroupResponse of(PurchaseGroupView v) {
        return new PurchaseGroupResponse(v.id(), v.name(), v.interest(), v.status(), v.total(), v.remaining(), v.myRemaining(),
                v.installmentAmountPerMonth(), v.lastDueDate(),
                v.divergence() == null ? null : new Divergence(v.divergence().declared(), v.divergence().computed(), v.divergence().difference()),
                v.members().stream().map(m -> new Member(m.transactionId(), new CardRef(m.cardId(), m.cardName()), m.description(),
                        m.totalInstallments(), m.remainingInstallments(), m.installmentAmount(), m.total(), m.remaining(), m.myRemaining(),
                        m.lastDueDate())).toList(),
                v.version(), v.createdAt(), v.updatedAt());
    }
}
