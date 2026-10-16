package com.moneylog.core.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.FxExceptions.FxInvalidException;
import com.moneylog.core.transaction.exception.FxExceptions.FxRateRequiredException;

@DisplayName("Moeda estrangeira como metadado (ADR-025)")
class ForeignChargeTest {

    private static final CurrencyCode USD = CurrencyCode.of("USD");

    @Test
    @DisplayName("USD 5,00 a 5,000000 com IOF de 350 bps vira 25,88, com um único arredondamento")
    void arredondaUmaVez() {
        ForeignCharge charge = ForeignCharge.of(Money.of("5.00", USD), new BigDecimal("5.000000"), 350);

        assertThat(charge.brl()).isEqualTo(Money.of("25.88"));
    }

    @Test
    @DisplayName("a cobrança seguinte com câmbio 5,2 usa 5,2 e a anterior permanece intacta")
    void cambioPorCobranca() {
        ForeignCharge first = ForeignCharge.of(Money.of("5.00", USD), new BigDecimal("5.000000"), null);
        ForeignCharge second = first.withRate(new BigDecimal("5.2"), null, null);

        assertThat(second.brl()).isEqualTo(Money.of("26.91"));
        assertThat(first.brl()).isEqualTo(Money.of("25.88"));
        assertThat(first.iofBps()).isEqualTo(ForeignCharge.DEFAULT_IOF_BPS);
    }

    @Test
    @DisplayName("moeda BRL com câmbio, câmbio não positivo e câmbio com mais de 6 casas são FX_INVALID")
    void entradasInvalidas() {
        assertThatThrownBy(() -> ForeignCharge.of(Money.of("5.00"), new BigDecimal("5"), 350)).isInstanceOf(FxInvalidException.class);
        assertThatThrownBy(() -> ForeignCharge.of(Money.of("5.00", USD), BigDecimal.ZERO, 350)).isInstanceOf(FxInvalidException.class);
        assertThatThrownBy(() -> ForeignCharge.of(Money.of("5.00", USD), new BigDecimal("5.1234567"), 350))
                .isInstanceOf(FxInvalidException.class);
        assertThatThrownBy(() -> ForeignCharge.of(Money.of("5.00", USD), new BigDecimal("5"), 10001)).isInstanceOf(FxInvalidException.class);
    }

    @Test
    @DisplayName("câmbio ausente com moeda estrangeira é FX_RATE_REQUIRED")
    void cambioObrigatorio() {
        assertThatThrownBy(() -> ForeignCharge.of(Money.of("5.00", USD), null, 350)).isInstanceOf(FxRateRequiredException.class);
    }
}
