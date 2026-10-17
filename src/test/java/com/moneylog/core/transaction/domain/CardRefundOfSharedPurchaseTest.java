package com.moneylog.core.transaction.domain;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidRefundOriginException;

@DisplayName("Estorno de compra rateada respeita o rateio (ADR-022 §6, PR 01)")
class CardRefundOfSharedPurchaseTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 15);
    private static final UUID ANA = UUID.fromString("00000000-0000-0000-0000-0000000000a1");

    private final FinanceScenarioFactory scenario = FinanceScenarioFactory.umCenarioBase();

    private Transaction sharedPurchase() {
        return CardTransactions.purchase(scenario.header(TODAY, "Mercado"), Money.of("100.00"), scenario.roxinho, scenario.alimentacao, 1,
                scenario.chain(), TODAY, SplitMode.BPS, List.of(new ShareLine(null, 5000, null), new ShareLine(ANA, 5000, null)));
    }

    private Transaction refund(Transaction original, String amount, Map<UUID, Money> thirds, Money already) {
        return CardTransactions.refundToCard(scenario.header(TODAY, "Estorno"), Money.of(amount), scenario.roxinho, scenario.alimentacao,
                new RefundOrigin(original, already), scenario.chain(), TODAY, thirds);
    }

    private Money total(Transaction tx, BookType type) {
        return Money.sum(tx.postings().stream().filter(p -> p.bookType() == type).map(Posting::amount).toList(), CurrencyCode.BRL);
    }

    @Test
    @DisplayName("estorno total de 100,00 num rateio 50/50: despesa -50, a receber da Ana -50, cartão +100, soma zero")
    void estornoTotal() {
        Transaction refund = refund(sharedPurchase(), "100.00", Map.of(), Money.zero(CurrencyCode.BRL));

        assertThat(total(refund, BookType.EXPENSE)).isEqualTo(Money.of("-50.00"));
        assertThat(total(refund, BookType.RECEIVABLE)).isEqualTo(Money.of("-50.00"));
        assertThat(total(refund, BookType.CARD)).isEqualTo(Money.of("100.00"));
        assertThat(Money.sum(refund.postings().stream().map(Posting::amount).toList(), CurrencyCode.BRL).isZero()).isTrue();
    }

    @Test
    @DisplayName("dois estornos parciais somam exatamente a parte da Ana e a minha, sem sobra nem falta")
    void estornosParciais() {
        Transaction original = sharedPurchase();
        Transaction first = refund(original, "30.01", Map.of(), Money.zero(CurrencyCode.BRL));
        Map<UUID, Money> after = SharePayables.refundedByPerson(original.id(), List.of(first));
        Transaction second = refund(original, "69.99", after, Money.of("30.01"));

        assertThat(total(first, BookType.RECEIVABLE).add(total(second, BookType.RECEIVABLE))).isEqualTo(Money.of("-50.00"));
        assertThat(total(first, BookType.EXPENSE).add(total(second, BookType.EXPENSE))).isEqualTo(Money.of("-50.00"));
    }

    @Test
    @DisplayName("borda de centavos: compra de 100,00 em 3x dividida em três, estornos de 0,01 e o resto fecham a parte de cada um sem passar do devido e com soma zero em cada estorno")
    void estornosDeUmCentavoEmTresPessoas() {
        UUID beto = UUID.fromString("00000000-0000-0000-0000-0000000000b2");
        Transaction original = CardTransactions.purchase(scenario.header(TODAY, "Mercado"), Money.of("100.00"), scenario.roxinho,
                scenario.alimentacao, 3, scenario.chain(), TODAY, SplitMode.EQUAL,
                List.of(new ShareLine(null, null, null), new ShareLine(ANA, null, null), new ShareLine(beto, null, null)));
        Money zero = Money.zero(CurrencyCode.BRL);

        Transaction first = refund(original, "0.01", Map.of(), zero);
        Transaction second = refund(original, "0.01", SharePayables.refundedByPerson(original.id(), List.of(first)), Money.of("0.01"));
        Transaction third = refund(original, "99.98", SharePayables.refundedByPerson(original.id(), List.of(first, second)), Money.of("0.02"));

        List<Transaction> refunds = List.of(first, second, third);
        for (Transaction refund : refunds) {
            assertThat(Money.sum(refund.postings().stream().map(Posting::amount).toList(), CurrencyCode.BRL).isZero()).isTrue();
            assertThat(total(refund, BookType.CARD).isPositive()).isTrue();
        }
        Money receivableRefunded = refunds.stream().map(r -> total(r, BookType.RECEIVABLE)).reduce(zero, Money::add);
        Money expenseRefunded = refunds.stream().map(r -> total(r, BookType.EXPENSE)).reduce(zero, Money::add);
        assertThat(receivableRefunded).isEqualTo(total(original, BookType.RECEIVABLE).negate());
        assertThat(expenseRefunded).isEqualTo(total(original, BookType.EXPENSE).negate());
        assertThat(total(first, BookType.RECEIVABLE).add(total(second, BookType.RECEIVABLE)).add(total(first, BookType.EXPENSE))
                .add(total(second, BookType.EXPENSE)).add(Money.of("0.02")).isZero()).isTrue();
    }

    @Test
    @DisplayName("compra sem rateio gera o mesmo estorno de sempre: despesa inteira")
    void semRateio() {
        Transaction plain = scenario.compraNoCartao("100.00", 1);

        Transaction refund = refund(plain, "40.00", Map.of(), Money.zero(CurrencyCode.BRL));

        assertThat(total(refund, BookType.EXPENSE)).isEqualTo(Money.of("-40.00"));
        assertThat(total(refund, BookType.RECEIVABLE).isZero()).isTrue();
    }

    @Test
    @DisplayName("Pix no crédito não tem edição, rateio, cancelamento de parcelas nem reembolso parcial")
    void pixNoCreditoImutavel() {
        CardCashOutPlan plan = CardCashOutPlan.of(Money.of("1000.00"), 4, Money.of("1120.00"), null);
        Transaction pix = CardCashOutTransactions.toAccount(scenario.header(TODAY, "Pix no crédito"), plan, LocalDate.of(2026, 11, 5),
                scenario.roxinho, scenario.nubank, scenario.alimentacao, scenario.chain(), TODAY);

        assertThat(pix.isCardCashOut()).isTrue();
        assertThat(sharedPurchase().isCardCashOut()).isFalse();
        assertThatThrownBy(() -> CardTransactions.editPurchaseDetails(pix, scenario.header(TODAY, "outra"), scenario.alimentacao, null))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> CardTransactions.reshare(pix, SplitMode.EQUAL, List.of(new ShareLine(null, null, null), new ShareLine(ANA, null, null)), Map.of()))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> CardTransactions.cancelRemainingInstallments(pix, scenario.chain(), TODAY, FinanceScenarioFactory.NOW))
                .isInstanceOf(RuntimeException.class);
        assertThatThrownBy(() -> refund(pix, "10.00", Map.of(), Money.zero(CurrencyCode.BRL))).isInstanceOf(InvalidRefundOriginException.class);
    }
}
