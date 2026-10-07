package com.moneylog.core.finance.app.transactions.handlers;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.DebtPayment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.LoanGiven;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.MoneyReceived;
import com.moneylog.core.transaction.domain.DebtOperation;
import com.moneylog.core.transaction.domain.DebtTransactions;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionFieldRequiredException;

/**
 * Os handlers das movimentações de dívida (Etapa 4, T76): emprestar ({@code LOAN_GIVEN}), receber ({@code MONEY_RECEIVED}, devolução
 * ou captação) e pagar ({@code DEBT_PAYMENT}). Mesmo ponto de extensão dos demais tipos; as regras são as de
 * {@link DebtTransactions} (nenhuma toca {@code EXPENSE}/{@code INCOME}: empréstimo não é consumo e devolução não é renda, PR 01).
 * A dívida que nasce com a movimentação volta em {@link BuiltTransaction#newDebts()} para ser gravada antes dela.
 */
public final class DebtTransactionHandlers {

    private DebtTransactionHandlers() {
    }

    public static List<TransactionTypeHandler<?>> all(HandlerSupport support) {
        return List.of(new LoanGivenHandler(support), new MoneyReceivedHandler(support), new DebtPaymentHandler(support));
    }

    private static TransactionHeader header(HandlerSupport support, com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common common,
                                            UUID id, TransactionOrigin origin) {
        return support.header(common, id, origin);
    }

    private static BuiltTransaction built(DebtOperation operation, boolean created) {
        return new BuiltTransaction(operation.transaction(), created ? List.of(operation.debt()) : List.of());
    }

    private record LoanGivenHandler(HandlerSupport support) implements TransactionTypeHandler<LoanGiven> {
        @Override
        public Class<LoanGiven> commandType() {
            return LoanGiven.class;
        }

        @Override
        public List<UUID> debtsToLock(LoanGiven c) {
            return c.debtId() == null ? List.of() : List.of(c.debtId());
        }

        @Override
        public List<UUID> personsToLock(LoanGiven c) {
            return c.newDebt() == null ? List.of() : List.of(c.newDebt().personId());
        }

        @Override
        public List<UUID> accountsToLock(LoanGiven c) {
            return List.of(c.accountId());
        }

        @Override
        public Transaction build(LoanGiven c, UUID id, TransactionOrigin origin, Transaction editing) {
            return buildWithEffects(c, id, origin, editing).transaction();
        }

        @Override
        public BuiltTransaction buildWithEffects(LoanGiven c, UUID id, TransactionOrigin origin, Transaction editing) {
            TransactionHeader header = header(support, c.common(), id, origin);
            Account account = support.account(c.userId(), c.accountId());
            if (c.debtId() != null) {
                // uma dívida existente nunca recebe outra origem (S-41): a fábrica recusa com o motivo certo
                Debt existing = support.debt(c.userId(), c.debtId());
                return built(DebtTransactions.loanGiven(header, c.common().amount(), account, existing), false);
            }
            if (c.newDebt() == null) {
                throw new TransactionFieldRequiredException("newDebt");
            }
            NewDebt newDebt = support.newDebt(c.userId(), c.newDebt(), header);
            return built(DebtTransactions.loanGiven(header, c.common().amount(), account, newDebt), true);
        }
    }

    private record MoneyReceivedHandler(HandlerSupport support) implements TransactionTypeHandler<MoneyReceived> {
        @Override
        public Class<MoneyReceived> commandType() {
            return MoneyReceived.class;
        }

        @Override
        public List<UUID> debtsToLock(MoneyReceived c) {
            return c.debtId() == null ? List.of() : List.of(c.debtId());
        }

        @Override
        public List<UUID> personsToLock(MoneyReceived c) {
            return c.newDebt() == null ? List.of() : List.of(c.newDebt().personId());
        }

        @Override
        public List<UUID> accountsToLock(MoneyReceived c) {
            return List.of(c.accountId());
        }

        @Override
        public Transaction build(MoneyReceived c, UUID id, TransactionOrigin origin, Transaction editing) {
            return buildWithEffects(c, id, origin, editing).transaction();
        }

        @Override
        public BuiltTransaction buildWithEffects(MoneyReceived c, UUID id, TransactionOrigin origin, Transaction editing) {
            if ((c.debtId() == null) == (c.newDebt() == null)) {
                throw new TransactionFieldRequiredException("debtId ou newDebt (exatamente um)");
            }
            TransactionHeader header = header(support, c.common(), id, origin);
            Account account = support.account(c.userId(), c.accountId());
            if (c.debtId() != null) {
                return built(DebtTransactions.moneyReceived(header, c.common().amount(), account, support.debt(c.userId(), c.debtId())), false);
            }
            NewDebt newDebt = support.newDebt(c.userId(), c.newDebt(), header);
            return built(DebtTransactions.moneyBorrowed(header, c.common().amount(), account, newDebt), true);
        }
    }

    private record DebtPaymentHandler(HandlerSupport support) implements TransactionTypeHandler<DebtPayment> {
        @Override
        public Class<DebtPayment> commandType() {
            return DebtPayment.class;
        }

        @Override
        public List<UUID> debtsToLock(DebtPayment c) {
            return c.debtId() == null ? List.of() : List.of(c.debtId());
        }

        @Override
        public List<UUID> accountsToLock(DebtPayment c) {
            return List.of(c.accountId());
        }

        @Override
        public Transaction build(DebtPayment c, UUID id, TransactionOrigin origin, Transaction editing) {
            if (c.debtId() == null) {
                throw new TransactionFieldRequiredException("debtId");
            }
            TransactionHeader header = header(support, c.common(), id, origin);
            return DebtTransactions.debtPayment(header, c.common().amount(), support.account(c.userId(), c.accountId()),
                    support.debt(c.userId(), c.debtId())).transaction();
        }
    }
}
