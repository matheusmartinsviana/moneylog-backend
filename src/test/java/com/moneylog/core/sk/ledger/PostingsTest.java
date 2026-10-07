package com.moneylog.core.sk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.sk.exception.BusinessRuleException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.ledger.LedgerExceptions.UnbalancedPostingsException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;

@DisplayName("Postings (razão de partidas dobradas)")
class PostingsTest {

    private static final UUID NUBANK = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final UUID ALIMENTACAO = UUID.fromString("00000000-0000-0000-0000-00000000c001");
    private static final LocalDate DIA = LocalDate.of(2026, 10, 10);

    @Test
    @DisplayName("despesa de 100: EXPENSE +100 / ACCOUNT −100 fecha em zero")
    void devePassarQuandoSomaZero() {
        List<Posting> postings = List.of(
                new Posting(BookRef.expense(ALIMENTACAO), Money.of("100.00"), DIA),
                new Posting(BookRef.account(NUBANK), Money.of("-100.00"), DIA));

        assertThatCode(() -> Postings.assertBalanced(postings)).doesNotThrowAnyException();
        assertThat(Postings.sum(postings)).isEqualTo(Money.of("0.00"));
    }

    @Test
    @DisplayName("lista vazia é balanceada")
    void deveAceitarListaVazia() {
        assertThatCode(() -> Postings.assertBalanced(List.of())).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("soma diferente de zero lança UnbalancedPostingsException (bug, não regra de negócio)")
    void deveRejeitarSomaDiferenteDeZero() {
        List<Posting> postings = List.of(
                new Posting(BookRef.expense(ALIMENTACAO), Money.of("100.00"), DIA),
                new Posting(BookRef.account(NUBANK), Money.of("-99.99"), DIA));

        assertThatThrownBy(() -> Postings.assertBalanced(postings))
                .isInstanceOf(UnbalancedPostingsException.class)
                .isInstanceOf(DomainException.class)
                .isNotInstanceOf(BusinessRuleException.class)
                .hasMessageContaining("BRL 0.01")
                .extracting("code").isEqualTo("LEDGER_UNBALANCED");
    }

    @Test
    @DisplayName("um posting sozinho nunca fecha")
    void deveRejeitarPostingSozinho() {
        assertThatThrownBy(() -> Postings.balanced(new Posting(BookRef.equity(), Money.of("1.00"), DIA)))
                .isInstanceOf(UnbalancedPostingsException.class);
    }

    @Test
    @DisplayName("balanced devolve a lista imutável quando fecha")
    void deveDevolverListaBalanceada() {
        List<Posting> postings = Postings.balanced(
                new Posting(BookRef.account(NUBANK), Money.of("20000.00"), DIA),
                new Posting(BookRef.equity(), Money.of("-20000.00"), DIA));

        assertThat(postings).hasSize(2);
        assertThatThrownBy(() -> postings.add(postings.get(0))).isInstanceOf(UnsupportedOperationException.class);
    }

    @Test
    @DisplayName("moedas diferentes no mesmo conjunto lançam CurrencyMismatchException")
    void deveRejeitarMoedasMisturadas() {
        List<Posting> postings = List.of(
                new Posting(BookRef.account(NUBANK), Money.of("1.00"), DIA),
                new Posting(BookRef.equity(), Money.of("-1.00", CurrencyCode.of("USD")), DIA));

        assertThatThrownBy(() -> Postings.assertBalanced(postings)).isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    @DisplayName("sum de lista vazia é erro")
    void deveRejeitarSomaVazia() {
        assertThatThrownBy(() -> Postings.sum(List.of())).isInstanceOf(InvalidPostingException.class);
    }
}
