package com.moneylog.core.income.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record IncomePreview(Competence month, Money gross, Money inss, Money irrf, List<Deduction> fixedDeductions,
                            List<Deduction> loanDeductions, Money net, Payment advance, Payment balance, Payment thirteenth,
                            List<TaxTableRef> taxTables) {

    public static final String INTEREST_NOT_SPLIT = "PAYROLL_INTEREST_NOT_SPLIT";

    public record Deduction(DeductionKind kind, String label, Money amount, UUID debtId, Money interestAmount, Money principalAmount,
                            List<String> warnings) {

        public Deduction {
            warnings = warnings == null ? List.of() : List.copyOf(warnings);
        }

        public Deduction(DeductionKind kind, String label, Money amount, UUID debtId) {
            this(kind, label, amount, debtId, null, null, List.of());
        }

        public boolean interestNotSplit() {
            return kind == DeductionKind.LOAN && interestAmount == null;
        }
    }

    public record Payment(LocalDate date, Money amount) {
    }

    public Payment payment(PayrollKind kind) {
        return switch (kind) {
            case ADVANCE -> advance;
            case BALANCE -> balance;
            case THIRTEENTH_FIRST -> thirteenth;
        };
    }
}
