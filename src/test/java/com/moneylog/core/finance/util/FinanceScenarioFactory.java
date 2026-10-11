package com.moneylog.core.finance.util;

import static com.moneylog.core.transaction.util.factory.TransactionTestFactory.compraNoCalendario;
import static com.moneylog.core.util.Ids.USER_ID;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.util.factory.CreditCardTestFactory;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.util.factory.PersonTestFactory;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.DebtOperation;
import com.moneylog.core.transaction.domain.DebtTransactions;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;

public final class FinanceScenarioFactory {

    public static final LocalDate BASE_DATE = LocalDate.of(2026, 10, 10);
    public static final Instant NOW = Instant.parse("2026-10-10T15:00:00Z");
    public static final LocalDate EXPECTED = LocalDate.of(2026, 11, 5);
    public static final UUID JOAO_DEBT_ID = UUID.fromString("00000000-0000-0000-0000-00000000db01");
    public static final UUID PEDRO_DEBT_ID = UUID.fromString("00000000-0000-0000-0000-00000000db02");

    public final Account nubank = AccountTestFactory.umaContaCorrente();
    public final Account xp = AccountTestFactory.umaContaSemSaldo();
    public final Account xpInvest = AccountTestFactory.umaContaDeInvestimento();
    public final CreditCard roxinho = CreditCardTestFactory.umCartao();
    public final Category alimentacao = CategoryTestFactory.umaCategoriaDeDespesa();
    public final Category salario = CategoryTestFactory.umaCategoriaDeReceita();
    public final List<Transaction> transactions = new ArrayList<>();
    public final List<Debt> debts = new ArrayList<>();
    private int sequence = 1;

    public static FinanceScenarioFactory umCenarioBase() {
        return new FinanceScenarioFactory();
    }

    public FinancialRecords records() {
        return new FinancialRecords(List.of(nubank, xp, xpInvest), List.of(roxinho), transactions, debts);
    }

    public InvoiceChain chain() {
        return CardInvoices.chainFor(roxinho, transactions);
    }

    public TransactionHeader header(LocalDate date, String description) {
        return TransactionHeader.of(UUID.fromString(String.format("00000000-0000-0000-0000-0000000f%04d", sequence++)), USER_ID, date, description);
    }

    public void estornar(Transaction tx, String reason, LocalDate today) {
        estornar(tx, reason, today, NOW);
    }

    public void estornar(Transaction tx, String reason, LocalDate today, Instant now) {
        VoidContext context = VoidContext.of(reason, now, today);
        if (tx.cardId() == null && tx.debtId() == null) {
            context = context.withAccounts(List.of(nubank, xp, xpInvest)).withActiveRefunds(Money.of("0.00"));
        }
        if (tx.cardId() != null) {
            context = context.withCard(roxinho, chain());
        }
        if (tx.debtId() != null) {
            context = context.withDebt(debts.stream().filter(d -> d.id().equals(tx.debtId())).findFirst().orElseThrow());
        }
        TransactionVoiding.voidTransaction(tx, context);
    }

    private Transaction add(Transaction tx) {
        transactions.add(tx);
        return tx;
    }

    public Transaction caso1DespesaPix() {
        return add(AccountTransactions.expense(header(BASE_DATE, "Restaurante"), Money.of("100.00"), nubank, alimentacao, PaymentMethod.PIX));
    }

    public Transaction caso2CompraParcelada() {
        return add(compraNoCalendario(header(BASE_DATE, "Notebook"), Money.of("1200.00"), roxinho, alimentacao, 6));
    }

    public Transaction compraNoCartao(String amount, int installments) {
        return add(compraNoCalendario(header(BASE_DATE, "Compra"), Money.of(amount), roxinho, alimentacao, installments));
    }

    public Transaction compraAVista1083() {
        return add(compraNoCalendario(header(LocalDate.of(2026, 10, 12), "Mercado"), Money.of("1083.00"), roxinho, alimentacao, 1));
    }

    public Transaction caso3PagarFatura(String amount) {
        return pagarFatura(amount, LocalDate.of(2026, 10, 25), LocalDate.of(2026, 11, 5));
    }

    public Transaction pagarFatura(String amount, LocalDate closing, LocalDate date) {
        return add(CardTransactions.invoicePayment(header(date, "Fatura"), Money.of(amount), nubank, roxinho, closing, chain(), date));
    }

    public Transaction caso4Transferencia() {
        return add(AccountTransactions.transfer(header(BASE_DATE, "Transferência"), Money.of("1000.00"), nubank, xp));
    }

    public Transaction caso5Investimento() {
        return add(AccountTransactions.investment(header(BASE_DATE, "CDB"), Money.of("10000.00"), InvestmentDirection.CONTRIBUTION, nubank, xpInvest));
    }

    public DebtOperation caso6Emprestimo() {
        DebtOperation op = DebtTransactions.loanGiven(header(BASE_DATE, "Empréstimo João"), Money.of("300.00"), nubank,
                new NewDebt(JOAO_DEBT_ID, PersonTestFactory.umJoao(), EXPECTED, null));
        add(op.transaction());
        debts.add(op.debt());
        return op;
    }

    public Transaction caso7Devolucao() {
        Debt debt = debts.stream().filter(d -> d.id().equals(JOAO_DEBT_ID)).findFirst().orElseThrow();
        return add(DebtTransactions.moneyReceived(header(BASE_DATE, "Devolução João"), Money.of("300.00"), nubank, debt).transaction());
    }

    public Debt caso8DividaSemMovimentacao() {
        Debt debt = Debt.withoutMovement(PEDRO_DEBT_ID, USER_ID, PersonTestFactory.umPedro(), DebtDirection.PAYABLE, Money.of("200.00"),
                BASE_DATE, EXPECTED, "show");
        debts.add(debt);
        return debt;
    }

    public Transaction caso9Reembolso(Transaction original) {
        return add(AccountTransactions.refundToAccount(header(BASE_DATE, "Reembolso"), Money.of("50.00"), nubank, alimentacao,
                RefundOrigin.firstRefundOf(original)));
    }
}
