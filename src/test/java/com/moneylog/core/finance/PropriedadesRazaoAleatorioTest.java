package com.moneylog.core.finance;

import static com.moneylog.core.finance.util.FinanceScenarioFactory.umCenarioBase;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.stream.LongStream;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.MethodSource;

import com.moneylog.core.card.domain.AvailableLimitCalculator;
import com.moneylog.core.card.domain.CreditCardInvoice;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.InvoiceStatus;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.InvalidInvoiceOperationException;
import com.moneylog.core.card.exception.CardExceptions.NoInstallmentsToCancelException;
import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.ConsumptionReport;
import com.moneylog.core.finance.domain.EntryLens;
import com.moneylog.core.finance.domain.EntryReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.finance.util.FinanceScenarioFactory;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.BookType;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardTransactionVoidPolicy;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.domain.TransactionVoidPolicy;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionVoidRefusedException;

@DisplayName("Propriedades do razão com sequências aleatórias de operações (T12, QA)")
class PropriedadesRazaoAleatorioTest {

    private static final int STEPS = 160;
    private static final LocalDate START = LocalDate.of(2026, 10, 1);

    static LongStream seeds() {
        return LongStream.rangeClosed(1, 40);
    }

    @ParameterizedTest(name = "semente {0}")
    @MethodSource("seeds")
    @DisplayName("o razão fecha em zero, não há dupla contagem e o livro CARD bate com as faturas")
    void deveManterAsInvariantes(long seed) {
        Random random = new Random(seed);
        FinanceScenarioFactory s = umCenarioBase();
        LocalDate today = START;
        int executed = 0;
        for (int step = 0; step < STEPS; step++) {
            today = today.plusDays(random.nextInt(5));
            Instant now = today.atTime(15, 0).toInstant(ZoneOffset.UTC);
            String op = apply(s, random, today, now);
            if (op != null) {
                executed++;
            }
            assertInvariants(s, today, "semente " + seed + ", passo " + step + " (" + op + ")");
        }
        assertThat(executed).isGreaterThan(STEPS / 2);
    }

    static String apply(FinanceScenarioFactory s, Random r, LocalDate today, Instant now) {
        int kind = r.nextInt(100);
        if (kind < 12) {
            s.transactions.add(AccountTransactions.expense(s.header(today, "Gasto"), cents(r, 1, 50_000), s.nubank, s.alimentacao,
                    PaymentMethod.values()[r.nextInt(PaymentMethod.values().length)]));
            return "EXPENSE";
        }
        if (kind < 18) {
            s.transactions.add(AccountTransactions.income(s.header(today, "Salário"), cents(r, 1, 500_000), s.nubank, s.salario,
                    PaymentMethod.PIX));
            return "INCOME";
        }
        if (kind < 24) {
            boolean ida = r.nextBoolean();
            s.transactions.add(AccountTransactions.transfer(s.header(today, "Transf"), cents(r, 1, 100_000),
                    ida ? s.nubank : s.xp, ida ? s.xp : s.nubank));
            return "TRANSFER";
        }
        if (kind < 30) {
            s.transactions.add(AccountTransactions.investment(s.header(today, "CDB"), cents(r, 1, 100_000),
                    r.nextBoolean() ? InvestmentDirection.CONTRIBUTION : InvestmentDirection.REDEMPTION, s.nubank, s.xpInvest));
            return "INVESTMENT";
        }
        if (kind < 55) {
            int n = 1 + r.nextInt(12);
            LocalDate date = today.minusDays(r.nextInt(25));
            InvoiceChain before = s.chain();
            Transaction purchase = CardTransactions.purchase(s.header(date, "Compra"), cents(r, n, 300_000), s.roxinho,
                    s.alimentacao, n, before, today);
            for (TransactionInstallment i : purchase.installments()) {
                assertItemAllocation(before, i.invoiceClosingDate(), i.lateEntry(), today, purchase.toString());
            }
            s.transactions.add(purchase);
            return "CARD_PURCHASE " + n + "x " + date;
        }
        if (kind < 63) {
            LocalDate date = today.minusDays(r.nextInt(15));
            InvoiceChain before = s.chain();
            Transaction refund = CardTransactions.refundToCard(s.header(date, "Estorno"), cents(r, 1, 80_000), s.roxinho,
                    s.alimentacao, null, before, today);
            assertItemAllocation(before, refund.invoiceClosingDate(), refund.lateEntry(), today, refund.toString());
            s.transactions.add(refund);
            return "REFUND card " + date;
        }
        if (kind < 82) {
            return pay(s, r, today);
        }
        if (kind < 93) {
            return voidSomething(s, r, today, now);
        }
        return cancelRemaining(s, r, today, now);
    }

    private static String pay(FinanceScenarioFactory s, Random r, LocalDate today) {
        InvoiceChain chain = s.chain();
        List<CreditCardInvoice> payable = new ArrayList<>();
        for (CreditCardInvoice invoice : chain.invoices()) {
            InvoiceStatus status = invoice.statusAt(today);
            if (status == InvoiceStatus.OPEN || status == InvoiceStatus.CLOSED || status == InvoiceStatus.OVERDUE) {
                payable.add(invoice);
            }
        }
        if (payable.isEmpty()) {
            payable.add(chain.current(today));
        }
        CreditCardInvoice target = payable.get(r.nextInt(payable.size()));
        Money outstanding = target.outstanding();
        Money amount = switch (r.nextInt(3)) {
            case 0 -> outstanding.isPositive() ? outstanding : cents(r, 1, 10_000);
            case 1 -> outstanding.cents() > 1 ? Money.ofCents(1 + r.nextLong(outstanding.cents() - 1), CurrencyCode.BRL) : cents(r, 1, 10_000);
            default -> outstanding.add(cents(r, 1, 50_000));
        };
        try {
            s.transactions.add(CardTransactions.invoicePayment(s.header(today, "Fatura"), amount, s.nubank, s.roxinho,
                    target.closingDate(), chain, today));
            return "INVOICE_PAYMENT " + amount + " em " + target.closingDate() + " (" + target.statusAt(today) + ")";
        } catch (InvalidInvoiceOperationException e) {
            return null;
        }
    }

    private static String voidSomething(FinanceScenarioFactory s, Random r, LocalDate today, Instant now) {
        List<Transaction> active = s.transactions.stream().filter(Transaction::isActive).toList();
        if (active.isEmpty()) {
            return null;
        }
        Transaction tx = active.get(r.nextInt(active.size()));
        try {
            s.estornar(tx, "estorno aleatório", today, now);
            return "VOID " + tx.type();
        } catch (TransactionVoidRefusedException e) {
            assertThat(tx.isActive()).as("estorno recusado não muda nada").isTrue();
            return null;
        }
    }

    private static String cancelRemaining(FinanceScenarioFactory s, Random r, LocalDate today, Instant now) {
        List<Transaction> purchases = s.transactions.stream()
                .filter(t -> t.isActive() && t.type() == TransactionType.CARD_PURCHASE).toList();
        if (purchases.isEmpty()) {
            return null;
        }
        Transaction purchase = purchases.get(r.nextInt(purchases.size()));
        try {
            CardTransactions.cancelRemainingInstallments(purchase, s.chain(), today, now);
            return "CANCEL_REMAINING";
        } catch (NoInstallmentsToCancelException e) {
            return null;
        }
    }

    private static Money cents(Random r, long min, long max) {
        return Money.ofCents(min + r.nextLong(max - min), CurrencyCode.BRL);
    }

    private static void assertItemAllocation(InvoiceChain before, LocalDate closing, boolean lateEntry, LocalDate today, String what) {
        InvoiceStatus status = before.statusOf(closing, today);
        if (status == InvoiceStatus.PAID && before.isUntouchedIssued(closing, today)) {
            assertThat(lateEntry).as("item em fatura emitida e vazia é lançamento tardio — " + what).isTrue();
            return;
        }
        assertThat(status).as("S-14: item nunca entra em fatura PAID — " + what).isNotEqualTo(InvoiceStatus.PAID);
        if (status == InvoiceStatus.CLOSED || status == InvoiceStatus.OVERDUE) {
            assertThat(lateEntry).as("item em fatura fechada é lançamento tardio — " + what).isTrue();
        }
    }

    private static void assertInvariants(FinanceScenarioFactory s, LocalDate today, String ctx) {
        FinancialRecords records = s.records();
        Ledger ledger = records.ledger();
        Money zero = Money.zero(CurrencyCode.BRL);

        assertThat(ledger.sum(e -> true)).as(ctx + ": Σ postings ativos").isEqualTo(zero);
        Map<String, Money> groups = new HashMap<>();
        for (LedgerEntry e : ledger.entries()) {
            String key = e.source().type() + "|" + e.source().sourceId() + "|" + e.source().installmentId();
            groups.merge(key, e.posting().amount(), Money::add);
        }
        groups.forEach((k, v) -> assertThat(v).as(ctx + ": grupo " + k).isEqualTo(zero));

        Money expected = zero;
        for (Transaction t : s.transactions) {
            if (t.type() == TransactionType.CARD_PURCHASE) {
                assertThat(Money.sum(t.installments().stream().map(TransactionInstallment::amount).toList(), CurrencyCode.BRL))
                        .as(ctx + ": Σ parcelas = valor").isEqualTo(t.amount());
            }
            if (!t.isActive()) {
                continue;
            }
            switch (t.type()) {
                case EXPENSE -> expected = expected.add(t.amount());
                case REFUND -> expected = expected.subtract(t.amount());
                case CARD_PURCHASE -> {
                    Money live = Money.sum(t.installments().stream().filter(i -> !i.isCanceled())
                            .map(TransactionInstallment::amount).toList(), CurrencyCode.BRL);
                    expected = expected.add(live);
                    Money posted = ledger.sum(e -> t.id().equals(e.source().transactionId()) && e.posting().bookType() == BookType.EXPENSE);
                    assertThat(posted).as(ctx + ": consumo da compra = parcelas vivas").isEqualTo(live);
                    assertThat(posted.isGreaterThan(t.amount())).as(ctx + ": compra contada mais de uma vez").isFalse();
                }
                default -> { }
            }
        }
        assertThat(ledger.sum(e -> e.posting().bookType() == BookType.EXPENSE)).as(ctx + ": Σ EXPENSE").isEqualTo(expected);
        for (LedgerEntry e : ledger.entries()) {
            if (e.source().type() != PostingSourceType.TRANSACTION) {
                continue;
            }
            Transaction t = records.transaction(e.source().transactionId()).orElseThrow();
            if (t.type() == TransactionType.INVOICE_PAYMENT || t.type() == TransactionType.TRANSFER
                    || t.type() == TransactionType.INVESTMENT) {
                assertThat(e.posting().bookType()).as(ctx + ": " + t.type() + " não toca resultado")
                        .isNotIn(BookType.EXPENSE, BookType.INCOME);
            }
        }

        InvoiceChain chain = s.chain();
        Money sumOutstanding = Money.sum(chain.invoices().stream().map(CreditCardInvoice::outstanding).toList(), CurrencyCode.BRL);
        Money cardBook = ledger.balanceAllDates(BookRef.card(s.roxinho.id()));
        assertThat(sumOutstanding.subtract(chain.creditBalance())).as(ctx + ": Σ outstanding − crédito = −CARD")
                .isEqualTo(cardBook.negate());
        Money sumTotal = Money.sum(chain.invoices().stream().map(CreditCardInvoice::total).toList(), CurrencyCode.BRL);
        Money sumPaid = Money.sum(chain.invoices().stream().map(CreditCardInvoice::paid).toList(), CurrencyCode.BRL);
        assertThat(sumTotal.subtract(sumPaid)).as(ctx + ": Σ total − Σ paid = −CARD").isEqualTo(cardBook.negate());
        assertThat(chain.invoices().stream().filter(i -> i.statusAt(today) == InvoiceStatus.OPEN).count())
                .as(ctx + ": no máximo uma fatura OPEN").isLessThanOrEqualTo(1);

        assertThat(AvailableLimitCalculator.calculate(s.roxinho, ledger.entries().stream().map(LedgerEntry::posting).toList()).available())
                .as(ctx + ": limite disponível").isEqualTo(s.roxinho.limit().add(cardBook));

        Competence from = Competence.from(START);
        Competence to = Competence.from(today).plusMonths(12);
        for (Competence c = from; !c.isAfter(to); c = c.plusMonths(1)) {
            ConsumptionReport report = ConsumptionReport.of(records, ledger, c);
            assertThat(report.expensesPaid().add(report.credit()).subtract(report.refunds())).as(ctx + ": identidade " + c)
                    .isEqualTo(report.consumption());
            assertThat(EntryReport.of(records, ledger, EntryLens.CONSUMPTION, c).total()).as(ctx + ": entries " + c)
                    .isEqualTo(report.consumption());
            assertThat(EntryReport.of(records, ledger, EntryLens.INCOME, c).total()).as(ctx + ": receita " + c)
                    .isEqualTo(report.income());
        }

        CashFlowReport flow = CashFlowReport.between(records, ledger, START, today.plusYears(1));
        Money cashPostings = ledger.sum(e -> e.source().type() == PostingSourceType.TRANSACTION
                && e.posting().bookType() == BookType.ACCOUNT
                && (e.posting().book().id().equals(s.nubank.id()) || e.posting().book().id().equals(s.xp.id())));
        assertThat(flow.net()).as(ctx + ": fluxo de caixa líquido").isEqualTo(cashPostings);
    }
}
