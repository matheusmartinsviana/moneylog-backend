package com.moneylog.core.finance.app.transactions.handlers;

import java.util.ArrayList;
import java.util.List;
import java.util.Objects;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Share;
import com.moneylog.core.transaction.domain.ExpenseOperation;
import com.moneylog.core.transaction.domain.ExpenseShare;
import com.moneylog.core.transaction.domain.NewDebt;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;

import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Adjustment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Expense;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Income;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Investment;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Refund;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Transfer;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Unavailable;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionTypeNotAvailableException;

/**
 * Os handlers dos tipos que só envolvem contas (Etapa 2): o mesmo comportamento que a fábrica tinha num {@code switch},
 * agora um handler por tipo (S-127). Comportamento idêntico: mesmas travas, na mesma ordem, e as mesmas fábricas do
 * domínio.
 */
public final class AccountTypeHandlers {

    private AccountTypeHandlers() {
    }

    public static List<TransactionTypeHandler<?>> all(HandlerSupport support) {
        return all(support, null);
    }

    /** Com o handler do crédito no cartão (Etapa 3); {@code null} deixa {@code REFUND} com {@code cardId} indisponível. */
    public static List<TransactionTypeHandler<?>> all(HandlerSupport support, CardRefundHandler cardRefund) {
        return List.of(new ExpenseHandler(support), new IncomeHandler(support), new TransferHandler(support),
                new InvestmentHandler(support), new AdjustmentHandler(support), new RefundHandler(support, cardRefund),
                new UnavailableHandler());
    }

    /**
     * Despesa. Com {@code shares} (S-40, S-137) é a despesa dividida: as dívidas nascem junto, uma por parte, e voltam em
     * {@link BuiltTransaction#newDebts()}. A edição de uma despesa dividida só muda descrição, categoria e observação: valor,
     * data, conta, meio de pagamento e divisão são os da despesa (senão {@code TRANSACTION_NOT_EDITABLE}, T78 CA7); a divisão
     * não se cria numa edição.
     */
    private record ExpenseHandler(HandlerSupport support) implements TransactionTypeHandler<Expense> {
        @Override
        public Class<Expense> commandType() {
            return Expense.class;
        }

        @Override
        public List<UUID> personsToLock(Expense c) {
            return c.shares().stream().map(share -> share.newDebt().personId()).distinct().sorted().toList();
        }

        @Override
        public List<UUID> accountsToLock(Expense c) {
            return List.of(c.accountId());
        }

        @Override
        public Transaction build(Expense c, UUID id, TransactionOrigin origin, Transaction editing) {
            return buildWithEffects(c, id, origin, editing).transaction();
        }

        @Override
        public BuiltTransaction buildWithEffects(Expense c, UUID id, TransactionOrigin origin, Transaction editing) {
            var header = support.header(c.common(), id, origin);
            Account account = support.account(c.userId(), c.accountId());
            Category category = support.category(c.userId(), c.categoryId(), editing, CategoryKind.EXPENSE);
            List<Debt> existing = editing == null ? List.of() : support.debtsOriginatedBy(c.userId(), editing.id());
            if (!existing.isEmpty()) {
                if (!c.shares().isEmpty() && !sameShares(c.shares(), existing)) {
                    throw new TransactionNotEditableException("a divisão de uma despesa dividida não muda; estorne a despesa "
                            + "(se nenhuma dívida tiver quitação) e lance de novo.");
                }
                if (!c.common().amount().equals(editing.amount()) || !c.common().date().equals(editing.date())
                        || !c.accountId().equals(editing.accountId()) || c.method() != editing.paymentMethod()) {
                    throw new TransactionNotEditableException("o valor, a data, a conta e o meio de pagamento de uma despesa dividida "
                            + "não mudam; só descrição, categoria e observação.");
                }
                return BuiltTransaction.of(AccountTransactions.expenseKeepingShares(header, c.common().amount(), account, category,
                        c.method(), existing));
            }
            if (c.shares().isEmpty()) {
                return BuiltTransaction.of(AccountTransactions.expense(header, c.common().amount(), account, category, c.method()));
            }
            if (editing != null) {
                throw new TransactionNotEditableException("a despesa só se divide ao ser criada.");
            }
            List<ExpenseShare> shares = new ArrayList<>();
            for (Share share : c.shares()) {
                NewDebt newDebt = support.newDebt(c.userId(), share.newDebt(), header);
                shares.add(new ExpenseShare(newDebt.debtId(), newDebt.person(), share.amount(), newDebt.expectedDate(),
                        newDebt.description()));
            }
            ExpenseOperation operation = AccountTransactions.expenseWithShares(header, c.common().amount(), account, category,
                    c.method(), shares);
            return new BuiltTransaction(operation.transaction(), operation.debts());
        }
    }

    /**
     * As partes do {@code PUT} são as mesmas das dívidas da despesa (S-241): mesma quantidade e, para cada parte, uma dívida distinta
     * com a mesma pessoa, o mesmo valor e a mesma previsão (a descrição só vale se informada). A ordem não importa. Repetir o que já
     * existe não muda nada; qualquer diferença continua recusada.
     */
    private static boolean sameShares(List<Share> shares, List<Debt> debts) {
        if (shares.size() != debts.size()) {
            return false;
        }
        List<Debt> remaining = new ArrayList<>(debts);
        for (Share share : shares) {
            Debt match = remaining.stream().filter(d -> d.personId().equals(share.newDebt().personId())
                    && d.principal().equals(share.amount())
                    && Objects.equals(d.expectedDate(), share.newDebt().expectedDate())
                    && (share.newDebt().description() == null || share.newDebt().description().strip().equals(d.description())))
                    .findFirst().orElse(null);
            if (match == null) {
                return false;
            }
            remaining.remove(match);
        }
        return true;
    }

    private record IncomeHandler(HandlerSupport support) implements TransactionTypeHandler<Income> {
        @Override
        public Class<Income> commandType() {
            return Income.class;
        }

        @Override
        public List<UUID> accountsToLock(Income c) {
            return List.of(c.accountId());
        }

        @Override
        public Transaction build(Income c, UUID id, TransactionOrigin origin, Transaction editing) {
            return AccountTransactions.income(support.header(c.common(), id, origin), c.common().amount(),
                    support.account(c.userId(), c.accountId()), support.category(c.userId(), c.categoryId(), editing, CategoryKind.INCOME), c.method(),
                    c.incomeCompetence());
        }
    }

    private record TransferHandler(HandlerSupport support) implements TransactionTypeHandler<Transfer> {
        @Override
        public Class<Transfer> commandType() {
            return Transfer.class;
        }

        @Override
        public List<UUID> accountsToLock(Transfer c) {
            return List.of(c.fromAccountId(), c.toAccountId());
        }

        @Override
        public Transaction build(Transfer c, UUID id, TransactionOrigin origin, Transaction editing) {
            return AccountTransactions.transfer(support.header(c.common(), id, origin), c.common().amount(),
                    support.account(c.userId(), c.fromAccountId()), support.account(c.userId(), c.toAccountId()));
        }
    }

    private record InvestmentHandler(HandlerSupport support) implements TransactionTypeHandler<Investment> {
        @Override
        public Class<Investment> commandType() {
            return Investment.class;
        }

        @Override
        public List<UUID> accountsToLock(Investment c) {
            return List.of(c.cashAccountId(), c.investmentAccountId());
        }

        @Override
        public Transaction build(Investment c, UUID id, TransactionOrigin origin, Transaction editing) {
            return AccountTransactions.investment(support.header(c.common(), id, origin), c.common().amount(), c.direction(),
                    support.account(c.userId(), c.cashAccountId()), support.account(c.userId(), c.investmentAccountId()));
        }
    }

    private record AdjustmentHandler(HandlerSupport support) implements TransactionTypeHandler<Adjustment> {
        @Override
        public Class<Adjustment> commandType() {
            return Adjustment.class;
        }

        @Override
        public List<UUID> accountsToLock(Adjustment c) {
            return List.of(c.accountId());
        }

        @Override
        public Transaction build(Adjustment c, UUID id, TransactionOrigin origin, Transaction editing) {
            return AccountTransactions.adjustment(support.header(c.common(), id, origin), c.common().amount(),
                    support.account(c.userId(), c.accountId()), c.direction(), c.kind(), c.reason());
        }
    }

    /**
     * Reembolso. Em conta ({@code accountId}) é o fluxo da Etapa 2; com {@code cardId} (crédito no cartão, T56) o handler
     * delega a {@link CardRefundHandler}. Sem o handler de cartão registrado, o crédito no cartão responde
     * {@code TRANSACTION_TYPE_NOT_AVAILABLE}.
     */
    static final class RefundHandler implements TransactionTypeHandler<Refund> {

        private final HandlerSupport support;
        private final CardRefundHandler cardHandler;

        RefundHandler(HandlerSupport support, CardRefundHandler cardHandler) {
            this.support = support;
            this.cardHandler = cardHandler;
        }

        @Override
        public Class<Refund> commandType() {
            return Refund.class;
        }

        @Override
        public List<UUID> transactionsToLock(Refund c) {
            return c.refundOfTransactionId() == null ? List.of() : List.of(c.refundOfTransactionId());
        }

        @Override
        public UUID cardToLock(Refund c) {
            return c.cardId();
        }

        @Override
        public List<UUID> accountsToLock(Refund c) {
            return c.accountId() == null ? List.of() : List.of(c.accountId());
        }

        @Override
        public Transaction build(Refund c, UUID id, TransactionOrigin origin, Transaction editing) {
            if (c.cardId() != null) {
                if (cardHandler == null) {
                    throw new TransactionTypeNotAvailableException(TransactionType.REFUND);
                }
                return cardHandler.build(c, id, origin, editing);
            }
            var refundOf = support.refundOrigin(c, editing);
            return AccountTransactions.refundToAccount(support.header(c.common(), id, origin), c.common().amount(),
                    support.account(c.userId(), c.accountId()), support.refundCategory(c, refundOf, editing), refundOf);
        }
    }

    /** Tipos que existem no domínio mas ainda não têm fluxo: {@code TRANSACTION_TYPE_NOT_AVAILABLE}. */
    private record UnavailableHandler() implements TransactionTypeHandler<Unavailable> {
        @Override
        public Class<Unavailable> commandType() {
            return Unavailable.class;
        }

        @Override
        public Transaction build(Unavailable c, UUID id, TransactionOrigin origin, Transaction editing) {
            throw new TransactionTypeNotAvailableException(c.type());
        }
    }
}
