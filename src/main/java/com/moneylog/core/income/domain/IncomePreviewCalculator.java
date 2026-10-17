package com.moneylog.core.income.domain;

import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.income.domain.IncomePreview.Deduction;
import com.moneylog.core.income.domain.IncomePreview.Payment;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

public final class IncomePreviewCalculator {

    private IncomePreviewCalculator() {
    }

    private static Deduction line(PayrollDeduction deduction, Money value, Money gross) {
        if (deduction.kind() != DeductionKind.LOAN) {
            return new Deduction(deduction.kind(), deduction.label(), value, deduction.debtId());
        }
        Money interest = deduction.interestFor(gross);
        if (interest == null) {
            return new Deduction(deduction.kind(), deduction.label(), value, deduction.debtId(), null, value,
                    List.of(IncomePreview.INTEREST_NOT_SPLIT));
        }
        return new Deduction(deduction.kind(), deduction.label(), value, deduction.debtId(), interest, value.subtract(interest), List.of());
    }

    public static IncomePreview calculate(IncomeProfile profile, Competence month, TaxTable inssTable, TaxTable irrfTable) {
        Money gross = profile.gross();
        Money inss = InssCalculator.calculate(inssTable, gross);
        Money irrf = IrrfCalculator.calculate(irrfTable, gross, inss, profile.dependents());
        List<Deduction> fixed = new ArrayList<>();
        List<Deduction> loans = new ArrayList<>();
        Money net = gross.subtract(inss).subtract(irrf);
        for (PayrollDeduction deduction : profile.deductions()) {
            if (!deduction.appliesIn(month)) {
                continue;
            }
            Money value = deduction.valueFor(gross);
            Deduction line = line(deduction, value, gross);
            (deduction.kind() == DeductionKind.LOAN ? loans : fixed).add(line);
            net = net.subtract(value);
        }
        if (net.isNegative()) {
            throw new IncomeProfileInvalidException("os descontos superam o bruto em " + month + ".");
        }
        Payment advance = null;
        Money balanceAmount = net;
        if (profile.advance() != null) {
            long advanceCents = Math.floorDiv(Math.multiplyExact(net.cents(), (long) profile.advance().percentBps()), 10_000L);
            Money advanceAmount = Money.ofCents(advanceCents, gross.currency());
            advance = new Payment(profile.advanceDate(month), advanceAmount);
            balanceAmount = net.subtract(advanceAmount);
        }
        Payment thirteenth = null;
        if (profile.thirteenthFirstDate(month) != null) {
            thirteenth = new Payment(profile.thirteenthFirstDate(month),
                    Money.ofCents(Math.floorDiv(gross.cents(), 2L), gross.currency()));
        }
        return new IncomePreview(month, gross, inss, irrf, fixed, loans, net, advance,
                new Payment(profile.balanceDate(month), balanceAmount), thirteenth, List.of(inssTable.ref(), irrfTable.ref()));
    }
}
