package com.moneylog.core.card.domain;

import static com.moneylog.core.util.Ids.USER_ID;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Random;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.exception.CardExceptions.HistoricalInvoiceNotClosedException;
import com.moneylog.core.card.exception.CardExceptions.InvoiceClosingDateNotFoundException;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionSource;

@DisplayName("Fatura histórica (ADR-014): valor da fatura passada pelo pagamento, sem consumo e sem dupla contagem")
class FaturaHistoricaTest {

    private static final LocalDate TODAY = LocalDate.of(2026, 10, 30);
    private final CreditCard card = CreditCardTestFactory.umCartao(); // fecha 25, vence 5
    private final Account account = AccountTestFactory.umaContaCorrente();

    private static long sum(List<Transaction> txs, BookType type) {
        return txs.stream().flatMap(t -> t.entries().stream()).filter(LedgerEntry::active).filter(e -> e.posting().bookType() == type)
                .mapToLong(e -> e.posting().amount().cents()).sum();
    }

    private Transaction item(List<Transaction> txs, LocalDate closing, String amount) {
        TransactionHeader header = new TransactionHeader(UUID.randomUUID(), USER_ID, closing, "Fatura histórica", null, TransactionOrigin.historical());
        Transaction tx = CardTransactions.historicalInvoiceItem(header, Money.of(amount), card, closing, CardInvoices.chainFor(card, txs), TODAY);
        txs.add(tx);
        return tx;
    }

    private Transaction pay(List<Transaction> txs, LocalDate closing, String amount, LocalDate date) {
        Transaction tx = CardTransactions.invoicePayment(TransactionHeader.of(UUID.randomUUID(), USER_ID, date, "Fatura"), Money.of(amount), account, card,
                closing, CardInvoices.chainFor(card, txs), TODAY);
        txs.add(tx);
        return tx;
    }

    @Test
    @DisplayName("fatura passada vazia (hoje 'paga') recebe o item sem lançamento tardio, fica com saldo, é paga de verdade e termina em CARD 0, sem consumo")
    void deveRegistrarEPagarFaturaPassada() {
        List<Transaction> txs = new ArrayList<>();
        LocalDate closing = LocalDate.of(2026, 8, 25);
        Transaction item = item(txs, closing, "1000.00");
        assertThat(item.origin().source()).isEqualTo(TransactionSource.HISTORICAL);
        assertThat(item.categoryId()).isNull();
        assertThat(item.installments()).hasSize(1);
        assertThat(item.installments().get(0).lateEntry()).isFalse();
        InvoiceChain chain = CardInvoices.chainFor(card, txs);
        assertThat(chain.statusOf(closing, TODAY)).isEqualTo(InvoiceStatus.OVERDUE);
        assertThat(chain.invoice(closing).outstanding()).isEqualTo(Money.of("1000.00"));
        pay(txs, closing, "1000.00", LocalDate.of(2026, 9, 3));
        chain = CardInvoices.chainFor(card, txs);
        assertThat(chain.statusOf(closing, TODAY)).isEqualTo(InvoiceStatus.PAID);
        assertThat(chain.creditBalance().isZero()).isTrue();
        assertThat(sum(txs, BookType.CARD)).isZero();
        assertThat(sum(txs, BookType.EXPENSE)).isZero();
        assertThat(sum(txs, BookType.ACCOUNT)).isEqualTo(-100_000);
        assertThat(sum(txs, BookType.EQUITY)).isEqualTo(100_000);
    }

    @Test
    @DisplayName("fechamento que não é do calendário e fatura que ainda não fechou são recusados; valor zero também")
    void deveRecusarEntradaInvalida() {
        List<Transaction> txs = new ArrayList<>();
        assertThatThrownBy(() -> item(txs, LocalDate.of(2026, 8, 24), "10.00")).isInstanceOf(InvoiceClosingDateNotFoundException.class);
        assertThatThrownBy(() -> item(txs, LocalDate.of(2026, 11, 25), "10.00")).isInstanceOf(HistoricalInvoiceNotClosedException.class);
        assertThatThrownBy(() -> item(txs, LocalDate.of(2026, 8, 25), "0.00")).isInstanceOf(RuntimeException.class);
        assertThat(txs).isEmpty();
    }

    @Test
    @DisplayName("propriedade (200 cenários): vários itens históricos pagos em partes — Σ lançamentos = 0, CARD 0, consumo 0, ACCOUNT = −Σ pago, limite disponível volta ao limite")
    void propriedadeDeNaoDuplaContagem() {
        Random random = new Random(20261008);
        for (int round = 0; round < 200; round++) {
            List<Transaction> txs = new ArrayList<>();
            int months = 1 + random.nextInt(8);
            long total = 0;
            for (int m = 0; m < months; m++) {
                LocalDate closing = LocalDate.of(2026, 1 + m, 25);
                long cents = 100 + random.nextInt(2_000_000);
                total += cents;
                Money amount = Money.ofCents(cents, CurrencyCode.BRL);
                item(txs, closing, amount.toPlainString());
                long first = cents / 3 + random.nextInt((int) (cents / 3 + 1));
                pay(txs, closing, Money.ofCents(first, CurrencyCode.BRL).toPlainString(), closing.plusDays(3));
                pay(txs, closing, Money.ofCents(cents - first, CurrencyCode.BRL).toPlainString(), closing.plusDays(5));
            }
            long all = sum(txs, BookType.CARD) + sum(txs, BookType.EXPENSE) + sum(txs, BookType.ACCOUNT) + sum(txs, BookType.EQUITY);
            assertThat(all).as("soma zero").isZero();
            assertThat(sum(txs, BookType.CARD)).isZero();
            assertThat(sum(txs, BookType.EXPENSE)).isZero();
            assertThat(sum(txs, BookType.ACCOUNT)).isEqualTo(-total);
            InvoiceChain chain = CardInvoices.chainFor(card, txs);
            for (CreditCardInvoice invoice : chain.invoices()) {
                assertThat(invoice.statusAt(TODAY)).isEqualTo(InvoiceStatus.PAID);
            }
            assertThat(chain.creditBalance().isZero()).isTrue();
        }
    }
}
