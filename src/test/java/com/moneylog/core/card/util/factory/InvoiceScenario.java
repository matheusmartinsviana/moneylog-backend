package com.moneylog.core.card.util.factory;

import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.util.Ids.USER_ID;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;

/**
 * Cenário do cartão Roxinho (fechamento 25, vencimento 5): a fatura ref. 2026-11 (25/09–24/10, fecha 25/10, vence
 * 05/11) com a parcela 1/6 de 200 (compra de 1.200 em 10/10) e uma compra à vista de 1.083 (12/10): total 1.283.
 * Mutável: as operações acrescentam movimentações e a cadeia é recalculada a partir delas.
 */
public final class InvoiceScenario {

    public static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    public static final LocalDate NOV_CLOSING = LocalDate.of(2026, 10, 25);
    public static final LocalDate DEC_CLOSING = LocalDate.of(2026, 11, 25);
    public static final UUID PURCHASE_6X_ID = UUID.fromString("00000000-0000-0000-0000-0000000a6001");
    public static final UUID PURCHASE_1083_ID = UUID.fromString("00000000-0000-0000-0000-0000000a6002");

    private final CreditCard card;
    private final List<Transaction> transactions = new ArrayList<>();
    private int sequence = 100;

    private InvoiceScenario(CreditCard card) {
        this.card = card;
    }

    /** Só a compra de 1.200 em 6x (fatura ref. nov com 200). */
    public static InvoiceScenario comCompraParcelada() {
        InvoiceScenario s = new InvoiceScenario(CreditCardTestFactory.umCartao());
        s.transactions.add(compraNoCalendario(header(PURCHASE_6X_ID, LocalDate.of(2026, 10, 10)), Money.of("1200.00"),
                s.card, CategoryTestFactory.umaCategoriaDeDespesa(), 6));
        return s;
    }

    /** Fatura ref. nov com 1.283 (parcela 1/6 de 200 + 1.083 à vista). */
    public static InvoiceScenario comFaturaDe1283() {
        InvoiceScenario s = comCompraParcelada();
        s.transactions.add(compraNoCalendario(header(PURCHASE_1083_ID, LocalDate.of(2026, 10, 12)), Money.of("1083.00"),
                s.card, CategoryTestFactory.umaCategoriaDeDespesa(), 1));
        return s;
    }

    public InvoiceChain chain() {
        return CardInvoices.chainFor(card, transactions);
    }

    public Transaction pay(String amount, LocalDate invoiceClosing, LocalDate date) {
        Transaction tx = CardTransactions.invoicePayment(header(nextId(), date), Money.of(amount), AccountTestFactory.umaContaCorrente(),
                card, invoiceClosing, chain(), date);
        transactions.add(tx);
        return tx;
    }

    public Transaction refund(String amount, LocalDate date, LocalDate today) {
        Transaction tx = CardTransactions.refundToCard(header(nextId(), date), Money.of(amount), card,
                CategoryTestFactory.umaCategoriaDeDespesa(), null, chain(), today);
        transactions.add(tx);
        return tx;
    }

    /** Reembolso ligado a uma compra (S-9): o teto é o cobrado menos os reembolsos ativos anteriores. */
    public Transaction refundOf(Transaction original, String amount, LocalDate date, LocalDate today) {
        Transaction tx = CardTransactions.refundToCard(header(nextId(), date), Money.of(amount), card,
                CategoryTestFactory.umaCategoriaDeDespesa(), new com.moneylog.core.transaction.domain.RefundOrigin(original,
                        activeRefundsOf(original)), chain(), today);
        transactions.add(tx);
        return tx;
    }

    /** Σ dos reembolsos ativos ligados à compra (o que o gateway devolve em {@code sumActiveRefundsOf}). */
    public Money activeRefundsOf(Transaction original) {
        return Money.sum(transactions.stream().filter(t -> t.isActive() && original.id().equals(t.refundOfId()))
                .map(Transaction::amount).toList(), CurrencyCode.BRL);
    }

    public Transaction buy(String amount, int installments, LocalDate date, LocalDate today) {
        Transaction tx = CardTransactions.purchase(header(nextId(), date), Money.of(amount), card,
                CategoryTestFactory.umaCategoriaDeDespesa(), installments, chain(), today);
        transactions.add(tx);
        return tx;
    }

    /** Cenário com outro cartão (outro limite), sem movimentações. */
    public static InvoiceScenario vazio(CreditCard card) {
        return new InvoiceScenario(card);
    }

    public Transaction buyByCalendar(String amount, int installments, LocalDate date) {
        Transaction tx = compraNoCalendario(header(nextId(), date), Money.of(amount), card,
                CategoryTestFactory.umaCategoriaDeDespesa(), installments);
        transactions.add(tx);
        return tx;
    }

    /** Estorna pelo ponto único (M1), com o cartão e a cadeia do cenário. */
    public void voidTx(Transaction tx, String reason, LocalDate today) {
        TransactionVoiding.voidTransaction(tx, VoidContext.of(reason, NOW, today).withCard(card, chain()));
    }

    /** Postings ativos de todas as movimentações do cenário. */
    public List<Posting> postings() {
        return transactions.stream().flatMap(t -> t.postings().stream()).toList();
    }

    /** Saldo do livro CARD em todas as datas (postings ativos). */
    public Money cardBookBalance() {
        return Money.sum(transactions.stream().flatMap(t -> t.postings().stream())
                .filter(p -> p.bookType() == BookType.CARD).map(Posting::amount).toList(), CurrencyCode.BRL);
    }

    public Transaction get(UUID id) {
        return transactions.stream().filter(t -> t.id().equals(id)).findFirst().orElseThrow();
    }

    public List<Transaction> transactions() {
        return transactions;
    }

    public CreditCard card() {
        return card;
    }

    private UUID nextId() {
        return UUID.fromString(String.format("00000000-0000-0000-0000-0000000b%04d", sequence++));
    }

    public static TransactionHeader header(UUID id, LocalDate date) {
        return TransactionHeader.of(id, USER_ID, date, null);
    }

}
