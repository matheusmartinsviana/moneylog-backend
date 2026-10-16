package com.moneylog.core.income.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public record PayrollDeduction(DeductionKind kind, String label, Money amount, Integer percentBps, LocalDate startsOn,
                               LocalDate endsOn, UUID debtId, Money interestAmount) {

    public static final int LABEL_MAX_LENGTH = 100;

    public PayrollDeduction(DeductionKind kind, String label, Money amount, Integer percentBps, LocalDate startsOn, LocalDate endsOn,
                            UUID debtId) {
        this(kind, label, amount, percentBps, startsOn, endsOn, debtId, null);
    }

    public PayrollDeduction {
        if (kind == null) {
            throw new IncomeProfileInvalidException("a rubrica precisa de tipo.");
        }
        label = label == null ? "" : label.strip();
        if (label.isEmpty() || label.length() > LABEL_MAX_LENGTH) {
            throw new IncomeProfileInvalidException("o nome da rubrica tem de 1 a " + LABEL_MAX_LENGTH + " caracteres.");
        }
        if ((amount == null) == (percentBps == null)) {
            throw new IncomeProfileInvalidException("a rubrica leva 'amount' ou 'percentBps', nunca os dois.");
        }
        if (amount != null) {
            if (!amount.isPositive()) {
                throw new IncomeProfileInvalidException("o valor da rubrica precisa ser maior que zero.");
            }
            AmountLimit.ensureWithinLimit(amount, "amount");
        }
        if (percentBps != null && (percentBps < 1 || percentBps > 10_000)) {
            throw new IncomeProfileInvalidException("o percentual da rubrica vai de 1 a 10000 bps.");
        }
        if (debtId != null && kind != DeductionKind.LOAN) {
            throw new IncomeProfileInvalidException("'debtId' só existe em rubrica LOAN.");
        }
        if (interestAmount != null) {
            if (kind != DeductionKind.LOAN) {
                throw new IncomeProfileInvalidException("'interestAmount' só existe em rubrica LOAN.");
            }
            if (interestAmount.isNegative()) {
                throw new IncomeProfileInvalidException("os juros da parcela não podem ser negativos.");
            }
            if (amount != null && !interestAmount.isLessThan(amount)) {
                throw new IncomeProfileInvalidException("os juros da parcela precisam ser menores que o valor da rubrica.");
            }
        }
        if (startsOn != null && endsOn != null && endsOn.isBefore(startsOn)) {
            throw new IncomeProfileInvalidException("o fim da rubrica não pode ser anterior ao início.");
        }
    }

    public boolean appliesIn(Competence competence) {
        return (startsOn == null || !startsOn.isAfter(competence.lastDay())) && (endsOn == null || !endsOn.isBefore(competence.firstDay()));
    }

    public Money interestFor(Money gross) {
        if (interestAmount == null) {
            return null;
        }
        if (!interestAmount.isLessThan(valueFor(gross))) {
            throw new IncomeProfileInvalidException("os juros da parcela precisam ser menores que o valor da rubrica.");
        }
        return interestAmount;
    }

    public Money valueFor(Money gross) {
        if (amount != null) {
            return amount;
        }
        return Money.ofCents(Math.floorDiv(Math.multiplyExact(gross.cents(), (long) percentBps), 10_000L), gross.currency());
    }
}
