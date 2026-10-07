package com.moneylog.core.importing.domain;

import static com.moneylog.core.importing.util.factory.ImportTestFactory.DATE;
import static com.moneylog.core.importing.util.factory.ImportTestFactory.NUBANK_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.importing.exception.ImportingExceptions.InvalidImportLineException;
import com.moneylog.core.sk.money.Money;

@DisplayName("ImportLine")
class ImportLineTest {

    @Test
    @DisplayName("o valor é positivo e o sinal vem da direção")
    void valorComSinal() {
        assertThat(ImportLine.of(NUBANK_ID, DATE, Money.of("10.00"), ImportDirection.OUT, "x").signedAmount()).isEqualTo(Money.of("-10.00"));
        assertThat(ImportLine.of(NUBANK_ID, DATE, Money.of("10.00"), ImportDirection.IN, "x").signedAmount()).isEqualTo(Money.of("10.00"));
    }

    @Test
    @DisplayName("rejeita valor zero ou negativo e campos obrigatórios ausentes")
    void deveValidar() {
        assertThatThrownBy(() -> ImportLine.of(NUBANK_ID, DATE, Money.of("0.00"), ImportDirection.OUT, "x")).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> ImportLine.of(NUBANK_ID, DATE, Money.of("-1.00"), ImportDirection.OUT, "x")).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> ImportLine.of(NUBANK_ID, DATE, null, ImportDirection.OUT, "x")).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> ImportLine.of(null, DATE, Money.of("1.00"), ImportDirection.OUT, "x")).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> ImportLine.of(NUBANK_ID, null, Money.of("1.00"), ImportDirection.OUT, "x")).isInstanceOf(InvalidImportLineException.class);
        assertThatThrownBy(() -> ImportLine.of(NUBANK_ID, DATE, Money.of("1.00"), null, "x"))
                .isInstanceOf(InvalidImportLineException.class)
                .extracting("code").isEqualTo("IMPORT_LINE_INVALID");
    }

    @Test
    @DisplayName("FITID em branco vira null e a descrição é normalizada sob demanda")
    void fitIdEmBranco() {
        ImportLine linha = new ImportLine(NUBANK_ID, DATE, Money.of("1.00"), ImportDirection.OUT, " Café ", "  ");

        assertThat(linha.fitId()).isNull();
        assertThat(linha.normalizedDescription()).isEqualTo("CAFE");
    }
}
