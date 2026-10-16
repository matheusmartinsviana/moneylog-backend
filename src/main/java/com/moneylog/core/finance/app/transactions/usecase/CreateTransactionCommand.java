package com.moneylog.core.finance.app.transactions.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AdjustmentDirection;
import com.moneylog.core.transaction.domain.AdjustmentKind;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.InvestmentDirection;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.core.transaction.domain.TransactionType;

public sealed interface CreateTransactionCommand {

    UUID userId();

    TransactionType type();

    record Common(UUID userId, Money amount, LocalDate date, String description, String notes) {
    }

    record NewDebtSpec(UUID personId, LocalDate expectedDate, String description) {
    }

    record Share(Money amount, NewDebtSpec newDebt) {
    }

    record Expense(Common common, UUID accountId, UUID categoryId, PaymentMethod method, List<Share> shares,
                   TransactionNature nature) implements CreateTransactionCommand {

        public Expense {
            shares = shares == null ? List.of() : List.copyOf(shares);
        }

        public Expense(Common common, UUID accountId, UUID categoryId, PaymentMethod method, List<Share> shares) {
            this(common, accountId, categoryId, method, shares, null);
        }

        public Expense(Common common, UUID accountId, UUID categoryId, PaymentMethod method) {
            this(common, accountId, categoryId, method, List.of());
        }

        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.EXPENSE;
        }
    }

    record Income(Common common, UUID accountId, UUID categoryId, PaymentMethod method, Competence incomeCompetence)
            implements CreateTransactionCommand {

        public Income(Common common, UUID accountId, UUID categoryId, PaymentMethod method) {
            this(common, accountId, categoryId, method, null);
        }

        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.INCOME;
        }
    }

    record Transfer(Common common, UUID fromAccountId, UUID toAccountId) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.TRANSFER;
        }
    }

    record Investment(Common common, UUID cashAccountId, UUID investmentAccountId, InvestmentDirection direction)
            implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.INVESTMENT;
        }
    }

    record Adjustment(Common common, UUID accountId, AdjustmentDirection direction, AdjustmentKind kind, String reason)
            implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.ADJUSTMENT;
        }
    }

    record Refund(Common common, UUID accountId, UUID categoryId, UUID refundOfTransactionId, UUID cardId)
            implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.REFUND;
        }
    }

    record CardPurchase(Common common, UUID cardId, UUID categoryId, int installments, SplitMode splitMode, List<ShareLine> shares,
                        ForeignCharge foreignCharge, String lastDigits) implements CreateTransactionCommand {

        public CardPurchase {
            shares = shares == null ? List.of() : List.copyOf(shares);
        }

        public CardPurchase(Common common, UUID cardId, UUID categoryId, int installments, SplitMode splitMode, List<ShareLine> shares,
                            ForeignCharge foreignCharge) {
            this(common, cardId, categoryId, installments, splitMode, shares, foreignCharge, null);
        }

        public CardPurchase(Common common, UUID cardId, UUID categoryId, int installments, SplitMode splitMode, List<ShareLine> shares) {
            this(common, cardId, categoryId, installments, splitMode, shares, null);
        }

        public CardPurchase(Common common, UUID cardId, UUID categoryId, int installments) {
            this(common, cardId, categoryId, installments, null, List.of());
        }

        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.CARD_PURCHASE;
        }
    }

    record LoanGiven(Common common, UUID accountId, NewDebtSpec newDebt, UUID debtId) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.LOAN_GIVEN;
        }
    }

    record MoneyReceived(Common common, UUID accountId, UUID debtId, NewDebtSpec newDebt) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.MONEY_RECEIVED;
        }
    }

    record DebtPayment(Common common, UUID accountId, UUID debtId) implements CreateTransactionCommand {
        @Override
        public UUID userId() {
            return common.userId();
        }

        @Override
        public TransactionType type() {
            return TransactionType.DEBT_PAYMENT;
        }
    }

    record Unavailable(UUID userId, TransactionType type) implements CreateTransactionCommand {
    }
}
