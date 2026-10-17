package com.moneylog.infra.income.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.income.domain.DeductionKind;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.provenance.Provenance;

public record IncomeProfileResponse(UUID id, String source, Money gross, int dependents, AdvanceResponse advance, int balanceDayOfMonth,
                                    List<DeductionResponse> deductions, ThirteenthResponse thirteenth, Provenance provenance, Status status, long version,
                                    Instant createdAt, Instant updatedAt) {

    public enum Status {
        ACTIVE,
        ARCHIVED
    }

    public record AdvanceResponse(int dayOfMonth, int percentBps) {
    }

    public record ThirteenthResponse(boolean enabled, int firstInstallmentMonth) {
    }

    public record DeductionResponse(DeductionKind kind, String label, Money amount, Integer percentBps, LocalDate startsOn,
                                    LocalDate endsOn, UUID debtId, Money interestAmount) {
    }

    public static IncomeProfileResponse of(Versioned<IncomeProfile> stored) {
        IncomeProfile p = stored.value();
        return new IncomeProfileResponse(p.id(), p.source(), p.gross(), p.dependents(),
                p.advance() == null ? null : new AdvanceResponse(p.advance().dayOfMonth(), p.advance().percentBps()),
                p.balanceDayOfMonth(),
                p.deductions().stream().map(d -> new DeductionResponse(d.kind(), d.label(), d.amount(), d.percentBps(), d.startsOn(),
                        d.endsOn(), d.debtId(), d.interestAmount())).toList(),
                new ThirteenthResponse(p.thirteenth().enabled(), p.thirteenth().firstInstallmentMonth()),
                p.provenance(), p.isArchived() ? Status.ARCHIVED : Status.ACTIVE, stored.version(), stored.createdAt(), stored.updatedAt());
    }
}
