package com.moneylog.core.transaction.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.DebtDirection;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;
import com.moneylog.core.transaction.exception.TransactionExceptions.AdjustmentReasonRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.IncomeCompetenceInvalidException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidRefundOriginException;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvestmentAccountsMismatchException;
import com.moneylog.core.transaction.exception.TransactionExceptions.NonPositiveAmountException;
import com.moneylog.core.transaction.exception.TransactionExceptions.PaymentMethodRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.RefundExceedsOriginalException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharePersonArchivedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesExceedAmountException;
import com.moneylog.core.transaction.exception.TransactionExceptions.SameAccountTransferException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransferAcrossLiquidityException;
import com.moneylog.core.transaction.exception.TransactionExceptions.YieldRequiresInvestmentAccountException;

public final class AccountTransactions {

    public static final int ADJUSTMENT_REASON_MAX_LENGTH = 500;

    private AccountTransactions() {
    }

    public static Transaction expense(TransactionHeader header, Money amount, Account account, Category category,
                                      PaymentMethod method) {
        return expense(header, amount, account, category, method, null);
    }

    public static Transaction expense(TransactionHeader header, Money amount, Account account, Category category,
                                      PaymentMethod method, TransactionNature nature) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.EXPENSE);
        requireCategory(header, category, CategoryKind.EXPENSE);
        requireMethod(method, TransactionType.EXPENSE);
        return Transaction.builder(header, TransactionType.EXPENSE, amount)
                .paymentMethod(method).account(account.id()).category(category.id()).nature(nature)
                .entries(entries(header,
                        new Posting(category.book(), amount, header.date()),
                        new Posting(account.book(), amount.negate(), header.date())))
                .build();
    }

    public static ExpenseOperation expenseWithShares(TransactionHeader header, Money amount, Account account, Category category,
                                                     PaymentMethod method, List<ExpenseShare> shares) {
        return expenseWithShares(header, amount, account, category, method, shares, null);
    }

    public static ExpenseOperation expenseWithShares(TransactionHeader header, Money amount, Account account, Category category,
                                                     PaymentMethod method, List<ExpenseShare> shares, TransactionNature nature) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.EXPENSE);
        requireCategory(header, category, CategoryKind.EXPENSE);
        requireMethod(method, TransactionType.EXPENSE);
        TransactionHeader.required(shares, "shares");
        if (shares.isEmpty()) {
            throw new SharesExceedAmountException();
        }
        Money total = Money.zero(amount.currency());
        for (ExpenseShare share : shares) {
            if (share == null || share.amount() == null || !share.amount().isPositive()
                    || !amount.currency().equals(share.amount().currency())) {
                throw new SharesExceedAmountException();
            }
            total = total.add(share.amount());
        }
        if (!total.isLessThan(amount)) {
            throw new SharesExceedAmountException();
        }
        List<Debt> debts = new ArrayList<>();
        for (ExpenseShare share : shares) {
            share.person().ensureBelongsTo(header.userId());
            if (share.person().isArchived()) {
                throw new SharePersonArchivedException();
            }
            debts.add(Debt.withMovement(share.debtId(), header.userId(), share.person(), DebtDirection.RECEIVABLE, share.amount(),
                    header.date(), share.expectedDate(), share.description() == null ? header.description() : share.description(),
                    header.id()));
        }
        return new ExpenseOperation(sharedExpense(header, amount, account, category, method, debts, nature), debts);
    }

    public static Transaction expenseKeepingShares(TransactionHeader header, Money amount, Account account, Category category,
                                                   PaymentMethod method, List<Debt> debts) {
        return expenseKeepingShares(header, amount, account, category, method, debts, null);
    }

    public static Transaction expenseKeepingShares(TransactionHeader header, Money amount, Account account, Category category,
                                                   PaymentMethod method, List<Debt> debts, TransactionNature nature) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.EXPENSE);
        requireCategory(header, category, CategoryKind.EXPENSE);
        requireMethod(method, TransactionType.EXPENSE);
        Money total = Money.zero(amount.currency());
        for (Debt debt : debts) {
            if (!header.id().equals(debt.originTransactionId()) || debt.direction() != DebtDirection.RECEIVABLE) {
                throw new SharesExceedAmountException();
            }
            total = total.add(debt.principal());
        }
        if (debts.isEmpty() || !total.isLessThan(amount)) {
            throw new SharesExceedAmountException();
        }
        return sharedExpense(header, amount, account, category, method, debts, nature);
    }

    private static Transaction sharedExpense(TransactionHeader header, Money amount, Account account, Category category,
                                             PaymentMethod method, List<Debt> debts, TransactionNature nature) {
        Money total = Money.zero(amount.currency());
        List<Posting> postings = new ArrayList<>();
        for (Debt debt : debts) {
            total = total.add(debt.principal());
            postings.add(new Posting(debt.book(), debt.principal(), header.date()));
        }
        postings.add(0, new Posting(category.book(), amount.subtract(total), header.date()));
        postings.add(new Posting(account.book(), amount.negate(), header.date()));
        return Transaction.builder(header, TransactionType.EXPENSE, amount)
                .paymentMethod(method).account(account.id()).category(category.id()).nature(nature)
                .entries(entries(header, postings.toArray(Posting[]::new)))
                .build();
    }

    public static Transaction income(TransactionHeader header, Money amount, Account account, Category category,
                                     PaymentMethod method) {
        return income(header, amount, account, category, method, null);
    }

    public static Transaction income(TransactionHeader header, Money amount, Account account, Category category,
                                     PaymentMethod method, Competence incomeCompetence) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.INCOME);
        requireCategory(header, category, CategoryKind.INCOME);
        requireMethod(method, TransactionType.INCOME);
        Competence competence = validIncomeCompetence(incomeCompetence);
        return Transaction.builder(header, TransactionType.INCOME, amount)
                .paymentMethod(method).account(account.id()).category(category.id()).incomeCompetence(competence)
                .entries(entries(header,
                        new Posting(account.book(), amount, header.date()),
                        new Posting(category.book(), amount.negate(), header.date(), competence)))
                .build();
    }

    static Competence validIncomeCompetence(Competence competence) {
        if (competence != null && (!FinancialDates.isInWindow(competence.firstDay()) || !FinancialDates.isInWindow(competence.lastDay()))) {
            throw new IncomeCompetenceInvalidException("use um mês entre 2000-01 e 2100-12.");
        }
        return competence;
    }

    public static Transaction transfer(TransactionHeader header, Money amount, Account from, Account to) {
        requireHeaderAndAmount(header, amount);
        requireUsableAccount(header, amount, from);
        requireUsableAccount(header, amount, to);
        if (from.id().equals(to.id())) {
            throw new SameAccountTransferException();
        }
        if (from.liquidity() != to.liquidity()) {
            throw new TransferAcrossLiquidityException();
        }
        return Transaction.builder(header, TransactionType.TRANSFER, amount)
                .account(from.id()).counterpartAccount(to.id())
                .entries(entries(header,
                        new Posting(to.book(), amount, header.date()),
                        new Posting(from.book(), amount.negate(), header.date())))
                .build();
    }

    public static Transaction investment(TransactionHeader header, Money amount, InvestmentDirection direction,
                                         Account cashAccount, Account investmentAccount) {
        requireHeaderAndAmount(header, amount);
        TransactionHeader.required(direction, "direction");
        requireUsableAccount(header, amount, cashAccount);
        requireUsableAccount(header, amount, investmentAccount);
        if (cashAccount.liquidity() != LiquidityClass.CASH || investmentAccount.liquidity() != LiquidityClass.INVESTMENT) {
            throw new InvestmentAccountsMismatchException();
        }
        Money toInvestment = direction == InvestmentDirection.CONTRIBUTION ? amount : amount.negate();
        return Transaction.builder(header, TransactionType.INVESTMENT, amount)
                .account(cashAccount.id()).counterpartAccount(investmentAccount.id()).investmentDirection(direction)
                .entries(entries(header,
                        new Posting(investmentAccount.book(), toInvestment, header.date()),
                        new Posting(cashAccount.book(), toInvestment.negate(), header.date())))
                .build();
    }

    public static Transaction adjustment(TransactionHeader header, Money amount, Account account, AdjustmentDirection direction,
                                         AdjustmentKind kind, String reason) {
        requireHeaderAndAmount(header, amount);
        TransactionHeader.required(direction, "direction");
        requireUsableAccount(header, amount, account);
        String validReason = TransactionHeader.optionalText(reason, "reason", ADJUSTMENT_REASON_MAX_LENGTH);
        if (validReason == null) {
            throw new AdjustmentReasonRequiredException();
        }
        AdjustmentKind validKind = kind == null ? AdjustmentKind.CORRECTION : kind;
        if (validKind == AdjustmentKind.YIELD && account.type() != AccountType.INVESTMENT) {
            throw new YieldRequiresInvestmentAccountException();
        }
        Money signed = direction == AdjustmentDirection.INCREASE ? amount : amount.negate();
        return Transaction.builder(header, TransactionType.ADJUSTMENT, amount)
                .account(account.id()).adjustment(direction, validKind, validReason)
                .entries(entries(header,
                        new Posting(account.book(), signed, header.date()),
                        new Posting(BookRef.equity(), signed.negate(), header.date())))
                .build();
    }

    public static Transaction adjustment(TransactionHeader header, Money amount, Account account, AdjustmentDirection direction,
                                         String reason) {
        return adjustment(header, amount, account, direction, null, reason);
    }

    public static Transaction refundToAccount(TransactionHeader header, Money amount, Account account, Category category,
                                              RefundOrigin refundOf) {
        return refundToAccount(header, amount, account, category, refundOf, Map.of());
    }

    public static Transaction refundToAccount(TransactionHeader header, Money amount, Account account, Category category,
                                              RefundOrigin refundOf, Map<UUID, Money> thirdsAlreadyRefunded) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.REFUND);
        requireCategory(header, category, CategoryKind.EXPENSE);
        validateRefundOrigin(header, amount, category, refundOf);
        return Transaction.builder(header, TransactionType.REFUND, amount)
                .account(account.id()).category(category.id())
                .refundOf(refundOf == null ? null : refundOf.original().id())
                .entries(LedgerEntry.activeGroup(PostingSource.transaction(header.id()),
                        refundPostings(header, amount, category, account.book(), refundOf, thirdsAlreadyRefunded)))
                .build();
    }

    static List<Posting> refundPostings(TransactionHeader header, Money amount, Category category, BookRef counterpart,
                                        RefundOrigin refundOf, Map<UUID, Money> thirdsAlreadyRefunded) {
        if (refundOf == null) {
            return List.of(new Posting(counterpart, amount, header.date()), new Posting(category.book(), amount.negate(), header.date()));
        }
        return SharePayables.refundPostings(refundOf.original(), amount, refundOf.alreadyRefunded(), thirdsAlreadyRefunded,
                category.book(), counterpart, header.date());
    }

    static void requireHeaderAndAmount(TransactionHeader header, Money amount) {
        TransactionHeader.required(header, "header");
        if (amount == null || !amount.isPositive()) {
            throw new NonPositiveAmountException();
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
    }

    static void requireUsableAccount(TransactionHeader header, Money amount, Account account) {
        TransactionHeader.required(account, "account");
        account.ensureBelongsTo(header.userId());
        account.ensureCanReceiveTransactions();
        if (!account.currency().equals(amount.currency())) {
            throw new CurrencyMismatchException(account.currency(), amount.currency());
        }
    }

    static void requireCashAccount(TransactionHeader header, Money amount, Account account, TransactionType type) {
        requireUsableAccount(header, amount, account);
        if (account.liquidity() != LiquidityClass.CASH) {
            throw new AccountNotCashException(type);
        }
    }

    static void requireCategory(TransactionHeader header, Category category, CategoryKind kind) {
        TransactionHeader.required(category, "category");
        category.ensureBelongsTo(header.userId());
        category.ensureCanReceiveTransactions();
        category.ensureKind(kind);
    }

    static void validateRefundOrigin(TransactionHeader header, Money amount, Category category, RefundOrigin refundOf) {
        if (refundOf == null) {
            return;
        }
        Transaction original = refundOf.original();
        original.ensureBelongsTo(header.userId());
        if (!original.isActive()) {
            throw new InvalidRefundOriginException("a movimentação original está estornada.");
        }
        if (original.type() != TransactionType.EXPENSE && original.type() != TransactionType.CARD_PURCHASE) {
            throw new InvalidRefundOriginException("só despesa ou compra no cartão pode ser reembolsada.");
        }
        if (original.isCardCashOut()) {
            throw new InvalidRefundOriginException("o Pix no crédito não tem reembolso; estorne a movimentação inteira.");
        }
        if (!category.id().equals(original.categoryId())) {
            throw new InvalidRefundOriginException("o reembolso volta para a categoria da despesa original.");
        }
        Money remaining = original.chargedAmount().subtract(refundOf.alreadyRefunded());
        if (amount.isGreaterThan(remaining)) {
            throw new RefundExceedsOriginalException();
        }
    }

    static void requireMethod(PaymentMethod method, TransactionType type) {
        if (method == null) {
            throw new PaymentMethodRequiredException(type);
        }
    }

    static List<LedgerEntry> entries(TransactionHeader header, Posting... postings) {
        return LedgerEntry.activeGroup(PostingSource.transaction(header.id()), List.of(postings));
    }

}
