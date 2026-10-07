package com.moneylog.core.card.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.HashSet;
import java.util.Set;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.util.factory.CreditCardTestFactory;

@DisplayName("Id determinístico da fatura (ADR-006)")
class InvoiceIdsTest {

    private static final UUID CARD = CreditCardTestFactory.ROXINHO_ID;
    private static final LocalDate CLOSING = LocalDate.of(2026, 10, 25);

    @Test
    @DisplayName("é o UUID v5 de 'cardId|closingDate' sob o namespace da ADR (vetor calculado fora do Java)")
    void deveSerOUuidV5DoNomeDocumentado() {
        assertThat(InvoiceIds.of(CARD, CLOSING)).isEqualTo(UUID.fromString("b2995573-243f-5626-9232-2a820ab6a829"));
    }

    @Test
    @DisplayName("traz versão 5 e variante RFC 4122")
    void deveTerVersaoEVariante() {
        UUID id = InvoiceIds.of(CARD, CLOSING);
        assertThat(id.version()).isEqualTo(5);
        assertThat(id.variant()).isEqualTo(2);
    }

    @Test
    @DisplayName("é sempre o mesmo para o mesmo par (propriedade, 500 fechamentos) e distinto entre fechamentos e cartões")
    void deveSerEstavelEDistinto() {
        Set<UUID> ids = new HashSet<>();
        for (int i = 0; i < 500; i++) {
            LocalDate closing = CLOSING.plusDays(i);
            UUID first = InvoiceIds.of(CARD, closing);
            assertThat(InvoiceIds.of(CARD, closing)).isEqualTo(first);
            ids.add(first);
        }
        assertThat(ids).hasSize(500);
        assertThat(InvoiceIds.of(UUID.fromString("00000000-0000-0000-0000-00000000d002"), CLOSING))
                .isNotEqualTo(InvoiceIds.of(CARD, CLOSING));
    }

    @Test
    @DisplayName("exige cartão e fechamento")
    void deveExigirOsDoisCampos() {
        assertThatThrownBy(() -> InvoiceIds.of(null, CLOSING)).isInstanceOf(NullPointerException.class);
        assertThatThrownBy(() -> InvoiceIds.of(CARD, null)).isInstanceOf(NullPointerException.class);
    }
}
