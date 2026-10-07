package com.moneylog.core.transaction.app.query;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.util.ListScenario;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Filtro da lista de movimentações (T28, S-71, S-80)")
class TransactionFilterTest {

    private final ListScenario s = new ListScenario();

    private static TransactionFilter filter(LocalDate from, LocalDate to, TransactionType type, UUID account, UUID category, PaymentMethod method,
                                            TransactionStatus status, String q) {
        return new TransactionFilter(from, to, type, account, category, method, status, q);
    }

    @Test
    @DisplayName("padrão: ACTIVE, sem limite de data, texto em branco = ausente")
    void deveTerPadroes() {
        TransactionFilter all = filter(null, null, null, null, null, null, null, "   ");

        assertThat(all.status()).isEqualTo(TransactionStatus.ACTIVE);
        assertThat(all.q()).isNull();
        assertThat(all.matches(s.pix)).isTrue();
        assertThat(all.matches(s.estornada)).isFalse();
    }

    @Test
    @DisplayName("período inclusivo nas duas pontas; from depois de to é recusado")
    void deveFiltrarPorPeriodo() {
        TransactionFilter outubro = filter(LocalDate.of(2026, 10, 1), LocalDate.of(2026, 10, 31), null, null, null, null, null, null);

        assertThat(outubro.matches(s.pix)).isTrue();
        assertThat(outubro.matches(s.novembro)).isFalse();
        assertThat(filter(LocalDate.of(2026, 10, 10), LocalDate.of(2026, 10, 10), null, null, null, null, null, null).matches(s.pix)).isTrue();
        assertThat(filter(LocalDate.of(2026, 10, 11), null, null, null, null, null, null, null).matches(s.pix)).isFalse();
        assertThatThrownBy(() -> filter(LocalDate.of(2026, 11, 1), LocalDate.of(2026, 10, 1), null, null, null, null, null, null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("conta casa como origem ou como contrapartida; tipo, categoria e método restringem")
    void deveFiltrarPorAtributos() {
        assertThat(filter(null, null, null, s.xp.id(), null, null, null, null).matches(s.transferencia)).isTrue();
        assertThat(filter(null, null, null, s.nubank.id(), null, null, null, null).matches(s.transferencia)).isTrue();
        assertThat(filter(null, null, null, s.xpInvest.id(), null, null, null, null).matches(s.aplicacao)).isTrue();
        assertThat(filter(null, null, null, s.xp.id(), null, null, null, null).matches(s.pix)).isFalse();
        assertThat(filter(null, null, TransactionType.TRANSFER, null, null, null, null, null).matches(s.pix)).isFalse();
        assertThat(filter(null, null, null, null, s.transporte.id(), null, null, null).matches(s.debito)).isTrue();
        assertThat(filter(null, null, null, null, s.transporte.id(), null, null, null).matches(s.pix)).isFalse();
        assertThat(filter(null, null, null, null, null, PaymentMethod.DEBIT, null, null).matches(s.debito)).isTrue();
        assertThat(filter(null, null, null, null, null, PaymentMethod.DEBIT, null, null).matches(s.transferencia)).isFalse();
    }

    @Test
    @DisplayName("q: contém, sem diferenciar maiúsculas; acentos não são normalizados; sem descrição não casa")
    void deveBuscarNaDescricao() {
        assertThat(filter(null, null, null, null, null, null, null, "MERCADO").matches(s.pix)).isTrue();
        assertThat(filter(null, null, null, null, null, null, null, "bairro").matches(s.pix)).isTrue();
        assertThat(filter(null, null, null, null, null, null, null, "salario").matches(s.receita)).isFalse();
        assertThat(filter(null, null, null, null, null, null, null, "SALÁRIO").matches(s.receita)).isTrue();
        assertThat(filter(null, null, null, null, null, null, null, "padaria").matches(s.pix)).isFalse();
    }

    @Test
    @DisplayName("status VOIDED casa só as estornadas")
    void deveFiltrarPorStatus() {
        TransactionFilter voided = filter(null, null, null, null, null, null, TransactionStatus.VOIDED, null);

        assertThat(voided.matches(s.estornada)).isTrue();
        assertThat(voided.matches(s.pix)).isFalse();
    }
}
