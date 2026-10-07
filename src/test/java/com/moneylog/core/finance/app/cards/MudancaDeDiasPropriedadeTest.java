package com.moneylog.core.finance.app.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.TreeMap;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.CardBillingChanges;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * T58 CA7: o invariante do cartão (Σ outstanding − creditCarry da última = −saldo CARD, T54 CA8) vale antes e depois da
 * mudança de dias; o consumo por competência não muda; todo item aponta para um fechamento do calendário novo. 100 cenários
 * aleatórios (semente fixa) de compras, créditos e pagamentos, com mudanças de fechamento e/ou vencimento em seguida.
 */
@DisplayName("Mudança de dias: invariante do cartão e consumo por competência (T58 CA7)")
class MudancaDeDiasPropriedadeTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 10);

    @Test
    @DisplayName("invariante e consumo preservados em 100 cenários, e os itens ficam em faturas do calendário novo")
    void devePreservarOInvarianteEOConsumo() {
        int changes = 0;
        for (long seed = 1; seed <= 100; seed++) {
            Random random = new Random(seed);
            InvoiceScenario s = InvoiceScenario.vazio(CreditCardTestFactory.umCartao());
            LocalDate today = START;
            for (int step = 0; step < 8; step++) {
                today = today.plusDays(random.nextInt(15));
                try {
                    switch (random.nextInt(4)) {
                        case 0, 1 -> s.buy(amount(random, 30, 900), 1 + random.nextInt(10), today.minusDays(random.nextInt(8)), today);
                        case 2 -> s.refund(amount(random, 5, 100), today.minusDays(random.nextInt(5)), today);
                        default -> {
                            List<CreditCardInvoice> invoices = s.chain().invoices();
                            if (!invoices.isEmpty()) {
                                CreditCardInvoice target = invoices.get(random.nextInt(invoices.size()));
                                s.pay(amount(random, 10, 400), target.closingDate(), today);
                            }
                        }
                    }
                } catch (DomainException expected) {
                    // recusado pelo domínio (fatura paga, não pagável...): segue
                }
            }
            Money balanceBefore = s.cardBookBalance();
            assertInvariant(s, balanceBefore, seed);
            Map<Competence, Money> consumptionBefore = consumption(s.transactions());

            int newClosing = 1 + random.nextInt(31);
            int newDue = 1 + random.nextInt(31);
            CardBillingChanges.changeBillingDays(s.card(), s.transactions(), newClosing, newDue, today);
            changes++;

            assertInvariant(s, s.cardBookBalance(), seed);
            assertThat(s.cardBookBalance()).as("semente %d: o saldo do livro CARD não muda", seed).isEqualTo(balanceBefore);
            assertThat(consumption(s.transactions())).as("semente %d: consumo por competência", seed).isEqualTo(consumptionBefore);
            s.chain(); // lança INVOICE_CHAIN_INCONSISTENT se algum item ficou fora do calendário novo
        }
        assertThat(changes).isEqualTo(100);
    }

    private static void assertInvariant(InvoiceScenario s, Money cardBalance, long seed) {
        List<CreditCardInvoice> invoices = s.chain().invoices();
        Money outstanding = Money.sum(invoices.stream().map(CreditCardInvoice::outstanding).toList(), CurrencyCode.BRL);
        assertThat(outstanding.subtract(s.chain().creditBalance())).as("semente %d", seed).isEqualTo(cardBalance.negate());
    }

    private static Map<Competence, Money> consumption(List<Transaction> transactions) {
        Map<Competence, Money> byCompetence = new TreeMap<>();
        for (Transaction t : transactions) {
            for (Posting p : t.postings()) {
                if (p.bookType() == BookType.EXPENSE) {
                    byCompetence.merge(p.competence(), p.amount(), Money::add);
                }
            }
        }
        return byCompetence;
    }

    private static String amount(Random random, int min, int max) {
        return Money.ofCents((min + random.nextInt(max - min)) * 100L + random.nextInt(100), CurrencyCode.BRL).toPlainString();
    }
}
