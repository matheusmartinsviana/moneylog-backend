package com.moneylog.core.finance.app.cards;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Random;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.util.factory.InvoiceScenario;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;

@DisplayName("Invariante do cartão: Σ outstanding − creditCarry da última = −saldo CARD (T54 CA8)")
class InvarianteDoCartaoPropriedadeTest {

    private static final LocalDate START = LocalDate.of(2026, 10, 10);
    private static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");

    @Test
    @DisplayName("vale depois de cada operação, em 100 cenários, com a data de hoje e com datas vencidas e futuras")
    void deveValerEmCemCenariosAleatorios() {
        int operations = 0;
        for (long seed = 1; seed <= 100; seed++) {
            Random random = new Random(seed);
            InvoiceScenario scenario = InvoiceScenario.vazio(com.moneylog.core.card.util.factory.CreditCardTestFactory.umCartao());
            LocalDate today = START;
            for (int step = 0; step < 18; step++) {
                today = today.plusDays(random.nextInt(25));
                try {
                    operate(scenario, random, today);
                    operations++;
                } catch (DomainException expected) {
                }
                assertConsumptionNeverNegative(scenario, seed, today);
                assertInvariant(scenario, seed, today);
                assertInvariant(scenario, seed, today.plusDays(45));
                assertInvariant(scenario, seed, today.minusDays(30));
            }
        }
        assertThat(operations).as("o gerador precisa exercitar o domínio, não só recusas").isGreaterThan(900);
    }

    private static void operate(InvoiceScenario s, Random random, LocalDate today) {
        switch (random.nextInt(6)) {
            case 0, 1 -> s.buy(amount(random, 20, 1500), 1 + random.nextInt(12), today.minusDays(random.nextInt(20)), today);
            case 2 -> {
                List<Transaction> purchases = s.transactions().stream()
                        .filter(t -> t.isActive() && t.type() == TransactionType.CARD_PURCHASE).toList();
                if (!purchases.isEmpty() && random.nextBoolean()) {
                    Transaction purchase = purchases.get(random.nextInt(purchases.size()));
                    long room = purchase.chargedAmount().subtract(s.activeRefundsOf(purchase)).cents();
                    if (room > 0) {
                        long cents = random.nextBoolean() ? room : 1 + (long) random.nextInt((int) Math.min(room, 20_000));
                        s.refundOf(purchase, Money.ofCents(cents, com.moneylog.core.sk.money.CurrencyCode.BRL).toPlainString(),
                                today.minusDays(random.nextInt(10)), today);
                    }
                } else {
                    s.refund(amount(random, 5, 200), today.minusDays(random.nextInt(10)), today);
                }
            }
            case 3, 4 -> {
                List<CreditCardInvoice> invoices = s.chain().invoices();
                if (!invoices.isEmpty()) {
                    CreditCardInvoice target = invoices.get(random.nextInt(invoices.size()));
                    boolean exact = random.nextBoolean();
                    String value = exact && target.outstanding().isPositive() ? target.outstanding().toPlainString()
                            : amount(random, 10, 900);
                    s.pay(value, target.closingDate(), today);
                }
            }
            default -> {
                List<Transaction> candidates = s.transactions().stream().filter(Transaction::isActive).toList();
                if (!candidates.isEmpty()) {
                    Transaction tx = candidates.get(random.nextInt(candidates.size()));
                    if (tx.type() == TransactionType.CARD_PURCHASE && random.nextBoolean()) {
                        CardTransactions.cancelRemainingInstallments(tx, s.chain(), today, NOW, s.activeRefundsOf(tx));
                    } else {
                        s.voidTx(tx, "teste", today);
                    }
                }
            }
        }
    }

    private static String amount(Random random, int min, int max) {
        return Money.ofCents((min + random.nextInt(max - min)) * 100L + random.nextInt(100), com.moneylog.core.sk.money.CurrencyCode.BRL)
                .toPlainString();
    }

    private static void assertConsumptionNeverNegative(InvoiceScenario s, long seed, LocalDate date) {
        for (Transaction purchase : s.transactions()) {
            if (purchase.isActive() && purchase.type() == TransactionType.CARD_PURCHASE) {
                assertThat(purchase.chargedAmount().subtract(s.activeRefundsOf(purchase)).isNegative())
                        .as("consumo da compra %s negativo (semente %d em %s)", purchase.id(), seed, date).isFalse();
            }
        }
    }

    private static void assertInvariant(InvoiceScenario s, long seed, LocalDate date) {
        List<CreditCardInvoice> invoices = s.chain().invoices();
        Money outstanding = Money.sum(invoices.stream().map(CreditCardInvoice::outstanding).toList(),
                com.moneylog.core.sk.money.CurrencyCode.BRL);
        Money carry = s.chain().creditBalance();
        assertThat(outstanding.subtract(carry)).as("semente %d em %s", seed, date).isEqualTo(s.cardBookBalance().negate());
    }
}
