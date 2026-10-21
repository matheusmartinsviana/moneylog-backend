package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.TransactionStatus;

@DisplayName("Mensagem pronta do Atalho (ADR-034, seção 9)")
class QuickEntryMessageTest {

    @Test
    void deveMontarGastoEEntrada() {
        assertThat(QuickEntryMessage.of(QuickEntryKind.EXPENSE, Money.of("35.50"), "Alimentação", "Carteira", TransactionStatus.ACTIVE))
                .isEqualTo("Gasto de R$ 35,50 em Alimentação (Carteira) registrado.");
        assertThat(QuickEntryMessage.of(QuickEntryKind.INCOME, Money.of("2500.00"), "Salário", "Carteira", TransactionStatus.ACTIVE))
                .isEqualTo("Entrada de R$ 2.500,00 em Salário (Carteira) registrada.");
    }

    @Test
    @DisplayName("estornado: diz o estado real")
    void deveDizerQueFoiEstornado() {
        assertThat(QuickEntryMessage.of(QuickEntryKind.EXPENSE, Money.of("35.50"), "Alimentação", "Carteira", TransactionStatus.VOIDED))
                .isEqualTo("Gasto de R$ 35,50 em Alimentação (Carteira) registrado e depois estornado.");
        assertThat(QuickEntryMessage.of(QuickEntryKind.INCOME, Money.of("2500.00"), "Salário", "Carteira", TransactionStatus.VOIDED))
                .isEqualTo("Entrada de R$ 2.500,00 em Salário (Carteira) registrada e depois estornada.");
    }
}
