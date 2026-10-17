package com.moneylog.core.transaction.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.card.domain.InstallmentPlan;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.CardCashOutExceptions.CardCashOutInvalidException;

public record CardCashOutPlan(Money principal, Money total, List<Money> installments, List<Money> principalParts,
                              List<Money> interestParts) {

    public static final int MAX_RATE_BPS = 10000;

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);

    public CardCashOutPlan {
        installments = List.copyOf(installments);
        principalParts = List.copyOf(principalParts);
        interestParts = List.copyOf(interestParts);
    }

    public static CardCashOutPlan of(Money principal, int count, Money totalWithInterest, Integer rateBps) {
        if (principal == null || !principal.isPositive() || !CurrencyCode.BRL.equals(principal.currency())) {
            throw new CardCashOutInvalidException("o principal precisa ser maior que zero, em BRL.");
        }
        AmountLimit.ensureWithinLimit(principal, "principal");
        InstallmentPlan.validateCount(count);
        if ((totalWithInterest == null) == (rateBps == null)) {
            throw new CardCashOutInvalidException("informe o total com juros ou a taxa mensal, não os dois nem nenhum.");
        }
        if (rateBps != null && (rateBps < 0 || rateBps > MAX_RATE_BPS)) {
            throw new CardCashOutInvalidException("a taxa mensal precisa estar entre 0 e " + MAX_RATE_BPS + " pontos-base.");
        }
        Money total = totalWithInterest != null ? totalWithInterest : totalFromRate(principal, count, rateBps);
        if (total.isLessThan(principal)) {
            throw new CardCashOutInvalidException("o total com juros não pode ser menor que o principal.");
        }
        AmountLimit.ensureWithinLimit(total, "totalWithInterest");
        List<Money> installments = total.allocate(count);
        List<Money> principalParts = principal.allocate(count);
        List<Money> interestParts = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            interestParts.add(installments.get(i).subtract(principalParts.get(i)));
        }
        return new CardCashOutPlan(principal, total, installments, principalParts, interestParts);
    }

    public Money interest() {
        return total.subtract(principal);
    }

    private static Money totalFromRate(Money principal, int count, int rateBps) {
        if (rateBps == 0) {
            return principal;
        }
        BigDecimal rate = BigDecimal.valueOf(rateBps).divide(BPS, MC);
        BigDecimal growth = BigDecimal.ONE.add(rate).pow(count, MC);
        BigDecimal payment = BigDecimal.valueOf(principal.cents()).multiply(rate, MC).multiply(growth, MC)
                .divide(growth.subtract(BigDecimal.ONE, MC), MC);
        long cents = payment.multiply(BigDecimal.valueOf(count), MC).setScale(0, RoundingMode.HALF_UP).longValueExact();
        return Money.ofCents(cents, principal.currency());
    }
}
