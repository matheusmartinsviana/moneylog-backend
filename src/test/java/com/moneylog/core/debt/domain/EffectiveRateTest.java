package com.moneylog.core.debt.domain;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.math.MathContext;
import java.util.Collections;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.Money;

@DisplayName("Taxa efetiva (TIR) do empréstimo")
class EffectiveRateTest {

    @Test
    @DisplayName("recebido 5.000 em 10 parcelas de 600 devolve a TIR mensal com tolerância de 1 bps")
    void tirMensal() {
        EffectiveRate rate = EffectiveRate.irr(Money.of("5000.00"), Collections.nCopies(10, Money.of("600.00"))).orElseThrow();

        BigDecimal monthly = BigDecimal.valueOf(rate.monthlyBps(), 4);
        BigDecimal present = BigDecimal.ZERO;
        BigDecimal base = BigDecimal.ONE.add(monthly);
        for (int k = 1; k <= 10; k++) {
            present = present.add(BigDecimal.valueOf(60000).divide(base.pow(k), MathContext.DECIMAL128));
        }
        assertThat(present.movePointLeft(2).subtract(new BigDecimal("5000")).abs()).isLessThan(new BigDecimal("5"));
        assertThat(rate.annualBps()).isEqualTo(
                BigDecimal.ONE.add(monthly).pow(12).subtract(BigDecimal.ONE).movePointRight(4).setScale(0, java.math.RoundingMode.HALF_UP)
                        .longValueExact());
    }

    @Test
    @DisplayName("parcelas que somam o principal têm taxa zero e sem cronograma a taxa é ausente")
    void taxaZeroEAusente() {
        assertThat(EffectiveRate.irr(Money.of("1000.00"), Collections.nCopies(4, Money.of("250.00"))).orElseThrow())
                .isEqualTo(new EffectiveRate(0, 0));
        assertThat(EffectiveRate.irr(Money.of("1000.00"), List.of())).isEmpty();
        assertThat(EffectiveRate.irr(null, List.of(Money.of("10.00")))).isEmpty();
    }
}
