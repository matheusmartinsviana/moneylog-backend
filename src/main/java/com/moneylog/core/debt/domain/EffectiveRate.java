package com.moneylog.core.debt.domain;

import java.math.BigDecimal;
import java.math.MathContext;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

import com.moneylog.core.sk.money.Money;

public record EffectiveRate(long monthlyBps, long annualBps) {

    public static final String METHOD = "IRR";

    private static final MathContext MC = MathContext.DECIMAL128;
    private static final BigDecimal BPS = BigDecimal.valueOf(10000);
    private static final BigDecimal UPPER_BOUND = BigDecimal.TEN;
    private static final int ITERATIONS = 200;

    public static Optional<EffectiveRate> irr(Money received, List<Money> installments) {
        if (received == null || !received.isPositive() || installments == null || installments.isEmpty()) {
            return Optional.empty();
        }
        Money total = Money.sum(installments, received.currency());
        if (!total.isGreaterThan(received)) {
            return Optional.of(new EffectiveRate(0, 0));
        }
        BigDecimal principal = BigDecimal.valueOf(received.cents());
        BigDecimal low = BigDecimal.ZERO;
        BigDecimal high = UPPER_BOUND;
        for (int i = 0; i < ITERATIONS; i++) {
            BigDecimal mid = low.add(high).divide(BigDecimal.valueOf(2), MC);
            if (presentValue(installments, mid).compareTo(principal) > 0) {
                low = mid;
            } else {
                high = mid;
            }
        }
        BigDecimal monthly = low.add(high).divide(BigDecimal.valueOf(2), MC);
        BigDecimal annual = BigDecimal.ONE.add(monthly).pow(12, MC).subtract(BigDecimal.ONE);
        return Optional.of(new EffectiveRate(bps(monthly), bps(annual)));
    }

    private static BigDecimal presentValue(List<Money> installments, BigDecimal rate) {
        BigDecimal base = BigDecimal.ONE.add(rate);
        BigDecimal discount = BigDecimal.ONE;
        BigDecimal total = BigDecimal.ZERO;
        for (Money installment : installments) {
            discount = discount.multiply(base, MC);
            total = total.add(BigDecimal.valueOf(installment.cents()).divide(discount, MC), MC);
        }
        return total;
    }

    private static long bps(BigDecimal rate) {
        return rate.multiply(BPS).setScale(0, RoundingMode.HALF_UP).longValueExact();
    }
}
