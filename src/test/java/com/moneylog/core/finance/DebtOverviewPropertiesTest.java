package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.UUID;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.card.exception.CardExceptions.OngoingFirstInvoiceNotPayableException;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.DebtOverview;
import com.moneylog.core.finance.domain.DebtOverviewCalculator;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.recurrence.domain.MonthlyEstimate;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;

/**
 * T245 (propriedades da visão de dívidas), sem dependência nova: para cada semente, sequências aleatórias de compras (1 a 12x), compras em
 * andamento, pagamentos totais/parciais/a maior, cancelamentos, estornos, reembolsos e dívidas. Depois de cada passo:
 * total = Σ partes; cartão = Σ planos + à vista + ajuste (ajuste ≤ 0); cartão = −saldo do livro CARD + crédito transportado; pessoas + outras
 * = −Σ PAYABLE; overdue + Σ pontos + undated + beyondHorizon = total; e criar compra em andamento sem categoria não cria consumo.
 */
@DisplayName("Propriedades da visão total de dívidas (T245, ADR-013)")
class DebtOverviewPropertiesTest {

    private static final Money ZERO = Money.zero(CurrencyCode.BRL);

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 40);
    }

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("nada contado em dobro e a curva fecha com o total")
    void deveManterAsIdentidades(long seed) {
        Random random = new Random(seed);
        FinanceScenarioFactory s = umCenarioBase();
        LocalDate today = LocalDate.of(2026, 10, 1);
        int debtSeq = 0;
        for (int step = 0; step < 120; step++) {
            today = today.plusDays(random.nextInt(5));
            Instant now = today.atTime(15, 0).toInstant(ZoneOffset.UTC);
            String ctx = "semente " + seed + ", passo " + step;
            int kind = random.nextInt(100);
            if (kind < 12) {
                Money consumptionBefore = consumption(s);
                try {
                    Transaction tx = CardTransactions.ongoingPurchase(s.header(today, "Em andamento").withOrigin(TransactionOrigin.ongoing()),
                            Money.ofCents(100 + random.nextInt(50_000), CurrencyCode.BRL), 12 + random.nextInt(24),
                            1 + random.nextInt(12), s.roxinho, null, s.chain(), today);
                    s.transactions.add(tx);
                    assertThat(consumption(s)).as(ctx + ": compra em andamento não cria consumo").isEqualTo(consumptionBefore);
                } catch (OngoingFirstInvoiceNotPayableException e) {
                    // fatura paga: recusa esperada
                }
            } else if (kind < 20) {
                Debt debt = random.nextBoolean()
                        ? Debt.withoutMovement(id(++debtSeq), USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE,
                                Money.ofCents(100 + random.nextInt(100_000), CurrencyCode.BRL), today,
                                random.nextBoolean() ? null : today.plusDays(random.nextInt(900) - 30), "x")
                        : Debt.withoutMovementToCreditor(id(++debtSeq), USER_ID, "Banco", Money.ofCents(100 + random.nextInt(100_000),
                                CurrencyCode.BRL), today, random.nextBoolean() ? null : today.plusDays(random.nextInt(900) - 30), "y");
                s.debts.add(debt);
            } else if (kind < 24 && !s.debts.isEmpty()) {
                Debt debt = s.debts.get(random.nextInt(s.debts.size()));
                if (debt.status() == com.moneylog.core.debt.domain.DebtStatus.OPEN) {
                    debt.cancel("perdão", today);
                }
            } else {
                PropriedadesRazaoAleatorioTest.apply(s, random, today, now);
            }
            assertIdentities(s, today, random.nextInt(36) + 1, ctx);
        }
    }

    private static UUID id(int n) {
        return UUID.fromString(String.format("00000000-0000-0000-0000-0000000d%04d", n));
    }

    private static Money consumption(FinanceScenarioFactory s) {
        return s.records().ledger().sum(e -> e.posting().bookType() == BookType.EXPENSE);
    }

    private static void assertIdentities(FinanceScenarioFactory s, LocalDate today, int months, String ctx) {
        FinancialRecords records = s.records();
        DebtOverview o = DebtOverviewCalculator.overview(records, Map.of(PersonTestFactory.PEDRO_ID, "Pedro"),
                new MonthlyEstimate(Money.of("1000.00"), ZERO, true), ZERO, ZERO, ZERO, today, months);

        assertThat(o.total()).as(ctx + ": total = Σ partes").isEqualTo(o.parts().cards().add(o.parts().people()).add(o.parts().other()));
        assertThat(o.total()).as(ctx + ": custo fixo e recebíveis fora do total").isEqualTo(DebtOverviewCalculator.total(records, today));
        for (DebtOverview.CardItem card : o.cards().items()) {
            Money plans = Money.sum(card.plans().stream().map(DebtOverview.Plan::remaining).toList(), CurrencyCode.BRL);
            assertThat(plans.add(card.otherCharges()).add(card.adjustment())).as(ctx + ": identidade do cartão").isEqualTo(card.total());
            assertThat(card.adjustment().isPositive()).as(ctx + ": ajuste ≤ 0").isFalse();
            assertThat(card.total()).isEqualTo(card.invoiced().add(card.future()));
        }
        // nada em dobro: o cartão é o saldo do livro CARD (mais o crédito transportado, que o estado líquido já abateu)
        Money cardBook = records.ledger().balanceAllDates(BookRef.card(s.roxinho.id()));
        assertThat(o.cards().total()).as(ctx + ": cartão = −livro CARD + crédito").isEqualTo(cardBook.negate().add(s.chain().creditBalance()));
        Money payables = records.ledger().sum(e -> e.posting().bookType() == BookType.PAYABLE);
        assertThat(o.parts().people().add(o.parts().other())).as(ctx + ": pessoas + outras = −Σ PAYABLE").isEqualTo(payables.negate());

        Money points = Money.sum(o.schedule().points().stream().map(DebtOverview.Point::total).toList(), CurrencyCode.BRL);
        assertThat(o.schedule().overdue().add(points).add(o.schedule().undated()).add(o.schedule().beyondHorizon()))
                .as(ctx + ": a curva fecha com o total").isEqualTo(o.total());
        assertThat(o.schedule().points()).hasSize(months);
        List<DebtOverview.Point> list = o.schedule().points();
        assertThat(list.getLast().remainingAfter()).isEqualTo(o.schedule().undated().add(o.schedule().beyondHorizon()));
    }
}
