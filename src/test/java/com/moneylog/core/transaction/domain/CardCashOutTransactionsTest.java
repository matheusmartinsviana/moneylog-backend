package com.moneylog.core.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtKind;
import com.moneylog.core.debt.domain.DebtStatus;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.CardCashOutExceptions.CardCashOutInvalidException;

@DisplayName("Pix no crédito como empréstimo (ADR-031, PR 01: dinheiro emprestado)")
class CardCashOutTransactionsTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final LocalDate FIRST_INVOICE = LocalDate.of(2026, 11, 5);

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private Money total(List<Posting> postings, BookType type) {
        return Money.sum(postings.stream().filter(p -> p.bookType() == type).map(Posting::amount).toList(), CurrencyCode.BRL);
    }

    private Transaction pixToMe(CardCashOutPlan plan) {
        return CardCashOutTransactions.toAccount(scenario.header(TODAY, "Pix no crédito"), plan, FIRST_INVOICE, scenario.roxinho,
                scenario.nubank, scenario.alimentacao, scenario.chain(), TODAY);
    }

    @Test
    @DisplayName("principal 1.000 em 4 de 280: conta +1.000, despesa só de juros (120), cartão -1.120, soma zero")
    void paraMim() {
        CardCashOutPlan plan = CardCashOutPlan.of(Money.of("1000.00"), 4, Money.of("1120.00"), null);

        Transaction tx = pixToMe(plan);
        List<Posting> postings = tx.postings();

        assertThat(total(postings, BookType.ACCOUNT)).isEqualTo(Money.of("1000.00"));
        assertThat(total(postings, BookType.EXPENSE)).isEqualTo(Money.of("120.00"));
        assertThat(total(postings, BookType.CARD)).isEqualTo(Money.of("-1120.00"));
        assertThat(Money.sum(postings.stream().map(Posting::amount).toList(), CurrencyCode.BRL).isZero()).isTrue();
        assertThat(total(postings, BookType.INCOME).isZero()).isTrue();
        assertThat(tx.installments()).extracting(i -> i.amount()).containsOnly(Money.of("280.00"));
        assertThat(postings.stream().filter(p -> p.bookType() == BookType.EXPENSE).map(Posting::amount))
                .containsOnly(Money.of("30.00"));
    }

    @Test
    @DisplayName("para a Ana o principal vira valor a receber, o juro é meu e a soma continua zero")
    void paraTerceiro() {
        CardCashOutPlan plan = CardCashOutPlan.of(Money.of("1000.00"), 4, Money.of("1120.00"), null);
        UUID debtId = UUID.randomUUID();

        Transaction tx = CardCashOutTransactions.toPerson(scenario.header(TODAY, "Pix no crédito"), plan, FIRST_INVOICE,
                scenario.roxinho, debtId, scenario.alimentacao, scenario.chain(), TODAY);

        assertThat(total(tx.postings(), BookType.RECEIVABLE)).isEqualTo(Money.of("1000.00"));
        assertThat(total(tx.postings(), BookType.ACCOUNT).isZero()).isTrue();
        assertThat(total(tx.postings(), BookType.EXPENSE)).isEqualTo(Money.of("120.00"));
        assertThat(total(tx.postings(), BookType.CARD)).isEqualTo(Money.of("-1120.00"));
        assertThat(Money.sum(tx.postings().stream().map(Posting::amount).toList(), CurrencyCode.BRL).isZero()).isTrue();
    }

    @Test
    @DisplayName("sem juros não há despesa e a soma fecha em zero mesmo com o centavo sobrando")
    void semJurosECentavo() {
        CardCashOutPlan plan = CardCashOutPlan.of(Money.of("1000.01"), 3, Money.of("1000.01"), null);

        Transaction tx = pixToMe(plan);

        assertThat(total(tx.postings(), BookType.EXPENSE).isZero()).isTrue();
        assertThat(Money.sum(tx.postings().stream().map(Posting::amount).toList(), CurrencyCode.BRL).isZero()).isTrue();
    }

    @Test
    @DisplayName("a taxa mensal gera o total com um arredondamento e total e taxa juntos são inválidos")
    void totalPelaTaxa() {
        CardCashOutPlan byRate = CardCashOutPlan.of(Money.of("1000.00"), 4, null, 0);
        assertThat(byRate.total()).isEqualTo(Money.of("1000.00"));

        CardCashOutPlan priced = CardCashOutPlan.of(Money.of("1000.00"), 4, null, 500);
        assertThat(priced.total().isGreaterThan(Money.of("1000.00"))).isTrue();
        assertThat(Money.sum(priced.installments(), CurrencyCode.BRL)).isEqualTo(priced.total());

        assertThatThrownBy(() -> CardCashOutPlan.of(Money.of("1000.00"), 4, Money.of("1120.00"), 500))
                .isInstanceOf(CardCashOutInvalidException.class);
        assertThatThrownBy(() -> CardCashOutPlan.of(Money.of("1000.00"), 4, null, null)).isInstanceOf(CardCashOutInvalidException.class);
        assertThatThrownBy(() -> CardCashOutPlan.of(Money.of("1000.00"), 4, Money.of("900.00"), null))
                .isInstanceOf(CardCashOutInvalidException.class);
    }

    @Test
    @DisplayName("o empréstimo para mim vive na fatura: não soma dívida nem se cancela; para a Ana segue a regra comum")
    void dividaDoPix() {
        Debt forMe = Debt.cardCashOutForMe(UUID.randomUUID(), com.moneylog.core.util.Ids.USER_ID, "Roxinho", Money.of("1000.00"), TODAY,
                "Pix no crédito", UUID.randomUUID(), " Caixa eletrônico ");

        assertThat(forMe.kind()).isEqualTo(DebtKind.CARD_CASH_OUT);
        assertThat(forMe.channel()).isEqualTo("Caixa eletrônico");
        assertThat(forMe.carriedByCard()).isTrue();
        assertThat(forMe.outstanding().isZero()).isTrue();
        assertThat(forMe.status()).isEqualTo(DebtStatus.OPEN);
        assertThatThrownBy(() -> forMe.cancel("motivo", TODAY)).isInstanceOf(
                com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException.class);
    }
}
