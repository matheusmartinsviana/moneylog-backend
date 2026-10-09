package com.moneylog.core.transaction.domain;

import static com.moneylog.core.account.util.factory.AccountTestFactory.umaContaCorrente;
import static com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeReceita;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.TX_ID;
import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.umHeader;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.exception.TransactionExceptions.IncomeCompetenceInvalidException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionStateInconsistentException;

/** ADR-017: a receita pode pertencer a outro mês; só a competência do livro INCOME muda, nunca a data nem o caixa. */
@DisplayName("Competência de renda na receita (ADR-017)")
class IncomeCompetenceDomainTest {

    private static final Account NUBANK = umaContaCorrente();
    private static final Category SALARIO = umaCategoriaDeReceita();
    private static final LocalDate ADIANTAMENTO = LocalDate.of(2026, 9, 16);

    private static Transaction receita(Competence competence) {
        return AccountTransactions.income(umHeader(), Money.of("1500.00"), NUBANK, SALARIO, PaymentMethod.PIX, competence);
    }

    private static Posting of(Transaction t, BookType type) {
        return t.postings().stream().filter(p -> p.bookType() == type).findFirst().orElseThrow();
    }

    @Test
    @DisplayName("a renda de outubro recebida em setembro: livro INCOME em 2026-10, conta (caixa) na data real")
    void deveSepararACompetenciaDaRendaDoCaixa() {
        Transaction t = AccountTransactions.income(umHeader(TX_ID, ADIANTAMENTO), Money.of("1500.00"), NUBANK, SALARIO, PaymentMethod.PIX,
                Competence.of(2026, 10));

        assertThat(t.incomeCompetence()).isEqualTo(Competence.of(2026, 10));
        assertThat(t.effectiveIncomeCompetence()).isEqualTo(Competence.of(2026, 10));
        assertThat(of(t, BookType.INCOME).competence()).isEqualTo(Competence.of(2026, 10));
        assertThat(of(t, BookType.INCOME).effectiveDate()).isEqualTo(ADIANTAMENTO);
        assertThat(of(t, BookType.ACCOUNT).effectiveDate()).isEqualTo(ADIANTAMENTO);
        assertThat(of(t, BookType.ACCOUNT).competence()).isEqualTo(Competence.of(2026, 9));
        // o razão continua fechando em zero e o valor é o mesmo
        assertThat(Money.sum(t.postings().stream().map(Posting::amount).toList(), t.amount().currency())).isEqualTo(Money.of("0.00"));
        assertThat(of(t, BookType.ACCOUNT).amount()).isEqualTo(Money.of("1500.00"));
    }

    @Test
    @DisplayName("ausente = o mês da data, como sempre (o caminho antigo não muda)")
    void deveManterOComportamentoAntigoSemCompetencia() {
        Transaction antiga = AccountTransactions.income(umHeader(TX_ID, ADIANTAMENTO), Money.of("1500.00"), NUBANK, SALARIO, PaymentMethod.PIX);
        Transaction nula = receita(null);

        assertThat(antiga.incomeCompetence()).isNull();
        assertThat(antiga.effectiveIncomeCompetence()).isEqualTo(Competence.of(2026, 9));
        assertThat(of(antiga, BookType.INCOME).competence()).isEqualTo(Competence.of(2026, 9));
        assertThat(nula.incomeCompetence()).isNull();
    }

    @Test
    @DisplayName("fora de 2000-01..2100-12: INCOME_COMPETENCE_INVALID; nos limites passa")
    void deveRecusarCompetenciaForaDaJanela() {
        assertThatThrownBy(() -> receita(Competence.of(1999, 12))).isInstanceOf(IncomeCompetenceInvalidException.class)
                .hasFieldOrPropertyWithValue("code", "INCOME_COMPETENCE_INVALID");
        assertThatThrownBy(() -> receita(Competence.of(2101, 1))).isInstanceOf(IncomeCompetenceInvalidException.class);
        assertThat(receita(Competence.of(2000, 1)).incomeCompetence()).isEqualTo(Competence.of(2000, 1));
        assertThat(receita(Competence.of(2100, 12)).incomeCompetence()).isEqualTo(Competence.of(2100, 12));
    }

    @Test
    @DisplayName("reidratar devolve a mesma competência; o estado e os lançamentos concordam")
    void deveReidratarACompetencia() {
        Transaction original = receita(Competence.of(2026, 11));

        Transaction restored = Transaction.restore(original.state(), original.entries());

        assertThat(restored.incomeCompetence()).isEqualTo(Competence.of(2026, 11));
        assertThat(restored.postings()).isEqualTo(original.postings());
    }

    @Test
    @DisplayName("estado gravado em que o lançamento da renda não bate com a competência declarada é recusado")
    void deveRecusarEstadoIncoerente() {
        Transaction original = receita(Competence.of(2026, 11));
        TransactionState declaraOutra = withIncomeCompetence(original.state(), Competence.of(2027, 1));
        TransactionState nenhuma = withIncomeCompetence(original.state(), null);

        assertThatThrownBy(() -> Transaction.restore(declaraOutra, original.entries())).isInstanceOf(TransactionStateInconsistentException.class);
        assertThatThrownBy(() -> Transaction.restore(nenhuma, original.entries())).isInstanceOf(TransactionStateInconsistentException.class);
    }

    @Test
    @DisplayName("só a receita tem competência de renda: despesa, transferência e ajuste recusam o estado")
    void deveRecusarCompetenciaForaDaReceita() {
        Transaction despesa = AccountTransactions.expense(umHeader(), Money.of("10.00"), NUBANK, com.moneylog.core.category.util.factory.CategoryTestFactory.umaCategoriaDeDespesa(),
                PaymentMethod.PIX);
        Transaction ajuste = AccountTransactions.adjustment(umHeader(), Money.of("10.00"), NUBANK, AdjustmentDirection.INCREASE, "motivo");

        for (Transaction t : List.of(despesa, ajuste)) {
            TransactionState comCompetencia = withIncomeCompetence(t.state(), Competence.of(2026, 10));
            assertThatThrownBy(() -> Transaction.restore(comCompetencia, t.entries())).isInstanceOf(TransactionStateInconsistentException.class);
        }
        // lançamentos de despesa/conta com competência de outro mês nem chegam a existir
        assertThatThrownBy(() -> despesa.postings().getFirst().inCompetence(Competence.of(2030, 1)))
                .isInstanceOf(com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException.class);
    }

    @Test
    @DisplayName("estornar a receita desativa o lançamento da renda em qualquer competência: nada some nem sobra")
    void deveEstornarSemSobrarRenda() {
        Transaction t = receita(Competence.of(2026, 11));
        Account conta = NUBANK;

        TransactionVoiding.voidTransaction(t, VoidContext.of("engano", java.time.Instant.parse("2026-10-10T15:00:00Z"), LocalDate.of(2026, 10, 10))
                .withAccounts(List.of(conta)).withActiveRefunds(Money.of("0.00")));

        assertThat(t.isActive()).isFalse();
        assertThat(t.postings()).isEmpty();
        assertThat(t.entries()).allMatch(e -> !e.active());
        assertThat(t.entries().stream().map(LedgerEntry::posting).filter(p -> p.bookType() == BookType.INCOME).findFirst().orElseThrow()
                .competence()).isEqualTo(Competence.of(2026, 11));
    }

    private static TransactionState withIncomeCompetence(TransactionState s, Competence competence) {
        return new TransactionState(s.id(), s.userId(), s.type(), s.amount(), s.date(), s.description(), s.notes(), s.origin(),
                s.paymentMethod(), s.accountId(), s.counterpartAccountId(), s.cardId(), s.categoryId(), s.debtId(), s.refundOfId(),
                s.investmentDirection(), s.adjustmentDirection(), s.adjustmentKind(), s.reason(), s.invoiceClosingDate(), s.lateEntry(),
                s.installments(), s.status(), s.voidReason(), s.voidedAt(), s.version(), competence);
    }
}
