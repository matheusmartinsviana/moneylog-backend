package com.moneylog.core.transaction.domain;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.debt.exception.DebtExceptions.DebtFieldRequiredException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.money.Money;

/**
 * Fábricas das movimentações de dívida (D1 §2.4, §2.6): {@code LOAN_GIVEN}, {@code MONEY_RECEIVED} (devolução ou
 * captação, S-6) e {@code DEBT_PAYMENT}. Nenhuma toca {@code EXPENSE}/{@code INCOME}: empréstimo não é consumo e
 * devolução não é renda (PR 01). O estorno passa por {@link TransactionVoiding} (ponto único, M1).
 */
public final class DebtTransactions {

    private DebtTransactions() {
    }

    /** Empréstimo dado: cria a dívida {@code RECEIVABLE}; {@code RECEIVABLE(dívida) +X / ACCOUNT −X}. */
    public static DebtOperation loanGiven(TransactionHeader header, Money amount, Account account, NewDebt newDebt) {
        return withNewDebt(header, amount, account, newDebt, TransactionType.LOAN_GIVEN, DebtDirection.RECEIVABLE);
    }

    /** Empréstimo ligado a uma dívida existente: só para recusar com o motivo certo (direção ou origem já existente). */
    public static DebtOperation loanGiven(TransactionHeader header, Money amount, Account account, Debt existing) {
        AccountTransactions.requireHeaderAndAmount(header, amount);
        requireDebt(header, existing).ensureDirection(DebtDirection.RECEIVABLE);
        throw existing.newOriginRefusal();
    }

    /** Captação (S-6): cria a dívida {@code PAYABLE}; {@code ACCOUNT +X / PAYABLE −X}, sem {@code INCOME}. */
    public static DebtOperation moneyBorrowed(TransactionHeader header, Money amount, Account account, NewDebt newDebt) {
        return withNewDebt(header, amount, account, newDebt, TransactionType.MONEY_RECEIVED, DebtDirection.PAYABLE);
    }

    /** Devolução de um recebível: {@code ACCOUNT +X / RECEIVABLE −X}, sem {@code INCOME}; quitação ≤ saldo devedor. */
    public static DebtOperation moneyReceived(TransactionHeader header, Money amount, Account account, Debt receivable) {
        return settlement(header, amount, account, receivable, TransactionType.MONEY_RECEIVED, DebtDirection.RECEIVABLE);
    }

    /** Pagamento de um passivo: {@code PAYABLE +X / ACCOUNT −X}; quitação ≤ saldo devedor. */
    public static DebtOperation debtPayment(TransactionHeader header, Money amount, Account account, Debt payable) {
        return settlement(header, amount, account, payable, TransactionType.DEBT_PAYMENT, DebtDirection.PAYABLE);
    }

    private static DebtOperation withNewDebt(TransactionHeader header, Money amount, Account account, NewDebt newDebt,
                                             TransactionType type, DebtDirection direction) {
        AccountTransactions.requireHeaderAndAmount(header, amount);
        AccountTransactions.requireCashAccount(header, amount, account, type);
        TransactionHeader.required(newDebt, "newDebt");
        Debt debt = Debt.withMovement(newDebt.debtId(), header.userId(), newDebt.person(), direction, amount, header.date(),
                newDebt.expectedDate(), newDebt.description(), header.id());
        Posting debtSide = new Posting(debt.book(), direction == DebtDirection.RECEIVABLE ? amount : amount.negate(), header.date());
        Transaction transaction = Transaction.builder(header, type, amount)
                .account(account.id()).debt(debt.id())
                .entries(AccountTransactions.entries(header, debtSide,
                        new Posting(BookRef.account(account.id()), debtSide.amount().negate(), header.date())))
                .build();
        return new DebtOperation(transaction, debt);
    }

    private static DebtOperation settlement(TransactionHeader header, Money amount, Account account, Debt debt,
                                            TransactionType type, DebtDirection direction) {
        AccountTransactions.requireHeaderAndAmount(header, amount);
        AccountTransactions.requireCashAccount(header, amount, account, type);
        requireDebt(header, debt).ensureDirection(direction);
        debt.ensureCanSettle(amount);
        Money accountSide = direction == DebtDirection.RECEIVABLE ? amount : amount.negate();
        Transaction transaction = Transaction.builder(header, type, amount)
                .account(account.id()).debt(debt.id())
                .entries(AccountTransactions.entries(header,
                        new Posting(BookRef.account(account.id()), accountSide, header.date()),
                        new Posting(debt.book(), accountSide.negate(), header.date())))
                .build();
        debt.registerSettlement(transaction.id(), amount);
        return new DebtOperation(transaction, debt);
    }

    private static Debt requireDebt(TransactionHeader header, Debt debt) {
        if (debt == null) {
            throw new DebtFieldRequiredException("debt");
        }
        debt.ensureBelongsTo(header.userId());
        return debt;
    }
}
