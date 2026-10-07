package com.moneylog.core.sk.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;

@DisplayName("Posting e BookRef")
class PostingTest {

    private static final UUID ID = UUID.fromString("00000000-0000-0000-0000-00000000a001");
    private static final LocalDate DIA = LocalDate.of(2027, 1, 31);

    @Test
    @DisplayName("a competência é o mês da data efetiva")
    void deveDerivarACompetencia() {
        Posting posting = new Posting(BookRef.expense(ID), Money.of("200.00"), DIA);

        assertThat(posting.competence()).isEqualTo(Competence.of(2027, 1));
        assertThat(posting.bookType()).isEqualTo(BookType.EXPENSE);
        assertThat(posting).hasToString("EXPENSE(" + ID + ") +200.00 @ 2027-01-31");
        assertThat(new Posting(BookRef.equity(), Money.of("-1.00"), DIA)).hasToString("EQUITY -1.00 @ 2027-01-31");
    }

    @Test
    @DisplayName("rejeita valor zero, livro ou data ausentes")
    void deveRejeitarPostingInvalido() {
        assertThatThrownBy(() -> new Posting(BookRef.account(ID), Money.of("0.00"), DIA)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new Posting(BookRef.account(ID), null, DIA)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new Posting(null, Money.of("1.00"), DIA)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new Posting(BookRef.account(ID), Money.of("1.00"), null)).isInstanceOf(InvalidPostingException.class);
    }

    @ParameterizedTest(name = "{0} exige id")
    @EnumSource(value = BookType.class, names = "EQUITY", mode = EnumSource.Mode.EXCLUDE)
    @DisplayName("todo livro, exceto EQUITY, exige id")
    void deveExigirId(BookType tipo) {
        assertThatThrownBy(() -> new BookRef(tipo, null)).isInstanceOf(InvalidPostingException.class);
        assertThat(new BookRef(tipo, ID).id()).isEqualTo(ID);
    }

    @Test
    @DisplayName("EQUITY não tem id e o tipo é obrigatório")
    void equityNaoTemId() {
        assertThatThrownBy(() -> new BookRef(BookType.EQUITY, ID)).isInstanceOf(InvalidPostingException.class);
        assertThatThrownBy(() -> new BookRef(null, ID)).isInstanceOf(InvalidPostingException.class);
        assertThat(BookRef.equity().id()).isNull();
    }

    @Test
    @DisplayName("fábricas apontam para o livro certo")
    void deveCriarPelasFabricas() {
        assertThat(BookRef.account(ID).type()).isEqualTo(BookType.ACCOUNT);
        assertThat(BookRef.card(ID).type()).isEqualTo(BookType.CARD);
        assertThat(BookRef.receivable(ID).type()).isEqualTo(BookType.RECEIVABLE);
        assertThat(BookRef.payable(ID).type()).isEqualTo(BookType.PAYABLE);
        assertThat(BookRef.expense(ID).type()).isEqualTo(BookType.EXPENSE);
        assertThat(BookRef.income(ID).type()).isEqualTo(BookType.INCOME);
    }

    @Test
    @DisplayName("patrimônio = ACCOUNT, CARD, RECEIVABLE, PAYABLE; resultado = EXPENSE, INCOME")
    void deveClassificarOsLivros() {
        assertThat(BookType.values()).filteredOn(BookType::isNetWorth)
                .containsExactlyInAnyOrder(BookType.ACCOUNT, BookType.CARD, BookType.RECEIVABLE, BookType.PAYABLE);
        assertThat(BookType.values()).filteredOn(BookType::isResult)
                .containsExactlyInAnyOrder(BookType.EXPENSE, BookType.INCOME);
    }
}
