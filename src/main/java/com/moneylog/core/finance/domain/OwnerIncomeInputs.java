package com.moneylog.core.finance.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.income.domain.PayrollKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.Competence;

public record OwnerIncomeInputs(UUID salaryCategoryId, List<Link> links, List<Preview> previews, Map<Competence, Money> loanApplied) {

    public record Link(UUID profileId, Competence competence, PayrollKind kind, UUID recurrenceId) {
    }

    public record Preview(UUID profileId, Competence competence, PayrollKind kind, LocalDate date, Money amount, Provenance provenance) {

        public Preview(UUID profileId, Competence competence, PayrollKind kind, LocalDate date, Money amount) {
            this(profileId, competence, kind, date, amount, null);
        }

        public Preview {
            provenance = Provenance.orDefault(provenance);
        }
    }

    public OwnerIncomeInputs {
        links = List.copyOf(links);
        previews = List.copyOf(previews);
        loanApplied = Map.copyOf(loanApplied);
    }

    public static OwnerIncomeInputs none() {
        return new OwnerIncomeInputs(null, List.of(), List.of(), Map.of());
    }
}
