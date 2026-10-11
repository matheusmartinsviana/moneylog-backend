package com.moneylog.core.finance.util;

import static com.moneylog.core.util.Ids.USER_ID;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.util.factory.AccountTestFactory;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.category.util.factory.CategoryTestFactory;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.RefundOrigin;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionVoiding;
import com.moneylog.core.transaction.domain.VoidContext;

public final class ListScenario {

    public final Account nubank = AccountTestFactory.umaContaCorrente();
    public final Account xp = AccountTestFactory.umaContaSemSaldo();
    public final Account xpInvest = AccountTestFactory.umaContaDeInvestimento();
    public final Category alimentacao = CategoryTestFactory.umaCategoriaDeDespesa();
    public final Category salario = CategoryTestFactory.umaCategoriaDeReceita();
    public final Category transporte = Category.create(UUID.fromString("00000000-0000-0000-0000-00000000c003"), USER_ID, "Transporte",
            CategoryKind.EXPENSE);
    public final List<Transaction> transactions = new ArrayList<>();
    public final Transaction pix;
    public final Transaction debito;
    public final Transaction receita;
    public final Transaction transferencia;
    public final Transaction aplicacao;
    public final Transaction reembolso;
    public final Transaction estornada;
    public final Transaction novembro;
    private int sequence = 1;

    public ListScenario() {
        pix = add(AccountTransactions.expense(header("2026-10-10", "Mercado do bairro"), Money.of("100.00"), nubank, alimentacao, PaymentMethod.PIX));
        debito = add(AccountTransactions.expense(header("2026-10-11", "Uber"), Money.of("40.00"), nubank, transporte, PaymentMethod.DEBIT));
        receita = add(AccountTransactions.income(header("2026-10-05", "Salário"), Money.of("3000.00"), nubank, salario, PaymentMethod.TRANSFER));
        transferencia = add(AccountTransactions.transfer(header("2026-10-10", "Para a XP"), Money.of("1000.00"), nubank, xp));
        aplicacao = add(AccountTransactions.investment(header("2026-10-15", "CDB"), Money.of("500.00"), InvestmentDirection.CONTRIBUTION, nubank, xpInvest));
        reembolso = add(AccountTransactions.refundToAccount(header("2026-10-10", "Estorno do mercado"), Money.of("50.00"), nubank, alimentacao,
                RefundOrigin.firstRefundOf(pix)));
        estornada = add(AccountTransactions.expense(header("2026-10-12", "Lançada errado"), Money.of("30.00"), nubank, alimentacao, PaymentMethod.PIX));
        TransactionVoiding.voidTransaction(estornada, VoidContext.of("engano", java.time.Instant.parse("2026-10-12T15:00:00Z"), LocalDate.of(2026, 10, 12))
                .withAccounts(java.util.List.of(nubank)).withActiveRefunds(Money.of("0.00")));
        novembro = add(AccountTransactions.expense(header("2026-11-05", "Conta de novembro"), Money.of("70.00"), nubank, alimentacao, PaymentMethod.BOLETO));
    }

    public FinancialRecords records() {
        return new FinancialRecords(List.of(nubank, xp, xpInvest), List.of(), transactions, List.of());
    }

    private Transaction add(Transaction transaction) {
        transactions.add(transaction);
        return transaction;
    }

    private TransactionHeader header(String date, String description) {
        return TransactionHeader.of(UUID.fromString(String.format("00000000-0000-0000-0000-0000000b%04d", sequence++)), USER_ID,
                LocalDate.parse(date), description);
    }
}
