package com.moneylog.core.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.exception.CardExceptions.InvoiceDocumentAmountInvalidException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

@DisplayName("Valor do documento da fatura (BUG-11)")
class InvoiceDocumentTest {

    private static final Money TOTAL = Money.of("1283.00");

    @Test
    @DisplayName("igual ao total calculado: sem divergência")
    void igual() {
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1283.00"))).isEmpty();
    }

    @Test
    @DisplayName("sem valor informado (limpo): sem divergência")
    void limpo() {
        assertThat(InvoiceDocument.divergence(TOTAL, null)).isEmpty();
    }

    @Test
    @DisplayName("documento maior que o total: diferença negativa; menor: positiva")
    void maiorEMenor() {
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1300.00")).orElseThrow().difference()).isEqualTo(Money.of("-17.00"));
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1280.50")).orElseThrow().difference()).isEqualTo(Money.of("2.50"));
    }

    @Test
    @DisplayName("tolerância: diferença igual à tolerância não excede; 1 centavo acima excede, nos dois sentidos")
    void tolerancia() {
        Money tolerance = Money.of("0.50");
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1283.50")).orElseThrow().exceeds(tolerance)).isFalse();
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1282.50")).orElseThrow().exceeds(tolerance)).isFalse();
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1283.51")).orElseThrow().exceeds(tolerance)).isTrue();
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1282.49")).orElseThrow().exceeds(tolerance)).isTrue();
        assertThat(InvoiceDocument.divergence(TOTAL, Money.of("1283.01")).orElseThrow().exceeds(Money.zero(CurrencyCode.BRL))).isTrue();
    }

    @Test
    @DisplayName("valor negativo é recusado; zero e positivo passam")
    void validacao() {
        assertThatThrownBy(() -> InvoiceDocument.validated(Money.of("-0.01"))).isInstanceOf(InvoiceDocumentAmountInvalidException.class);
        assertThat(InvoiceDocument.validated(Money.of("0.00"))).isEqualTo(Money.of("0.00"));
        assertThat(InvoiceDocument.validated(Money.of("1283.00"))).isEqualTo(Money.of("1283.00"));
    }
}
