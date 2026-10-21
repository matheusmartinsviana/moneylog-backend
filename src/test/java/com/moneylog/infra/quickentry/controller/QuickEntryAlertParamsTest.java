package com.moneylog.infra.quickentry.controller;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Parâmetros do aviso de lançamento rápido")
class QuickEntryAlertParamsTest {

    private final UUID id = UUID.randomUUID();

    private TransactionDetails details(TransactionType type, String amount, String description) {
        Transaction transaction = mock(Transaction.class);
        when(transaction.id()).thenReturn(id);
        when(transaction.type()).thenReturn(type);
        when(transaction.amount()).thenReturn(Money.of(amount));
        when(transaction.description()).thenReturn(description);
        TransactionDetails details = mock(TransactionDetails.class);
        when(details.transaction()).thenReturn(transaction);
        return details;
    }

    @Test
    @DisplayName("gasto: tipo, valor decimal, descrição e id da movimentação, e nada além disso")
    void deveMontarOGasto() {
        Map<String, String> params = QuickEntryAlertParams.of(details(TransactionType.EXPENSE, "54.60", "Mercado"));

        assertThat(params).containsOnly(Map.entry("type", "EXPENSE"), Map.entry("amount", "54.60"), Map.entry("description", "Mercado"),
                Map.entry("transactionId", id.toString()));
    }

    @Test
    @DisplayName("corte por code point: emoji na fronteira não é partido")
    void naoDevePartirEmoji() {
        String text = "a".repeat(QuickEntryAlertParams.DESCRIPTION_MAX - 1) + "\uD83D\uDE00\uD83D\uDE00";

        String cut = QuickEntryAlertParams.of(details(TransactionType.EXPENSE, "1.00", text)).get("description");

        assertThat(cut.codePointCount(0, cut.length())).isEqualTo(QuickEntryAlertParams.DESCRIPTION_MAX);
        assertThat(cut).endsWith("\uD83D\uDE00").doesNotContain("\uD83D\uDE00\uD83D");
    }

    @Test
    @DisplayName("entrada vira INCOME; sem descrição a chave some; descrição longa é cortada")
    void deveMontarAEntradaEOmitirOuCortarADescricao() {
        assertThat(QuickEntryAlertParams.of(details(TransactionType.INCOME, "2500.00", " "))).containsOnlyKeys("type", "amount", "transactionId")
                .containsEntry("type", "INCOME");
        assertThat(QuickEntryAlertParams.of(details(TransactionType.EXPENSE, "1.00", "x".repeat(200))).get("description"))
                .hasSize(QuickEntryAlertParams.DESCRIPTION_MAX);
    }
}
