package com.moneylog.core.transaction.domain;

import java.util.ArrayList;
import java.util.List;

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
import com.moneylog.core.sk.money.MoneyExceptions.CurrencyMismatchException;
import com.moneylog.core.transaction.exception.TransactionExceptions.AccountNotCashException;
import com.moneylog.core.transaction.exception.TransactionExceptions.AdjustmentReasonRequiredException;
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

/**
 * Fábricas dos tipos que só envolvem contas (D1 §2.4): {@code EXPENSE}, {@code INCOME}, {@code TRANSFER},
 * {@code INVESTMENT}, {@code ADJUSTMENT} e {@code REFUND} em conta. Todas validam dono (referência de outro usuário =
 * não encontrado), arquivamento, moeda e valor &gt; 0 até o teto de S-78, e geram postings com origem
 * {@code TRANSACTION}.
 */
public final class AccountTransactions {

    public static final int ADJUSTMENT_REASON_MAX_LENGTH = 500;

    private AccountTransactions() {
    }

    /** Gasto pago de conta: {@code EXPENSE(cat) +X / ACCOUNT −X}. Compra no crédito é {@code CARD_PURCHASE}. */
    public static Transaction expense(TransactionHeader header, Money amount, Account account, Category category,
                                      PaymentMethod method) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.EXPENSE);
        requireCategory(header, category, CategoryKind.EXPENSE);
        requireMethod(method, TransactionType.EXPENSE);
        return Transaction.builder(header, TransactionType.EXPENSE, amount)
                .paymentMethod(method).account(account.id()).category(category.id())
                .entries(entries(header,
                        new Posting(category.book(), amount, header.date()),
                        new Posting(account.book(), amount.negate(), header.date())))
                .build();
    }

    /**
     * Despesa dividida (D1 §2.6.1, S-40, S-137): paguei {@code amount} e cada pessoa das {@code shares} me deve a parte dela.
     * Postings {@code EXPENSE(cat) +própria / RECEIVABLE(dívida i) +parte i / ACCOUNT −amount}: o caixa é o valor inteiro (o
     * mesmo da linha do extrato), o consumo é só a parte do usuário e o patrimônio cai pela parte dele. A despesa é a
     * <b>origem</b> de cada dívida ({@code originTransactionId}) e segue as guardas de estorno delas (S-36). Quando a
     * pessoa paga, é {@code MONEY_RECEIVED} contra a dívida: caixa +, consumo intocado e <b>nunca receita</b> (PR 01).
     *
     * <p>Cada parte é maior que zero e Σ das partes fica abaixo de {@code amount} ({@code SHARES_EXCEED_AMOUNT}); mais de uma
     * parte para a mesma pessoa vira duas dívidas; pessoa arquivada → {@code SHARE_PERSON_ARCHIVED}.
     */
    public static ExpenseOperation expenseWithShares(TransactionHeader header, Money amount, Account account, Category category,
                                                     PaymentMethod method, List<ExpenseShare> shares) {
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
        return new ExpenseOperation(sharedExpense(header, amount, account, category, method, debts), debts);
    }

    /**
     * A despesa dividida reconstruída com as dívidas que já existem (edição de descrição, categoria e observação, T78 CA7):
     * valor, divisão e dívidas não mudam, só os postings regravados na categoria nova.
     *
     * @param debts as dívidas originadas pela despesa ({@code originTransactionId = header.id()})
     */
    public static Transaction expenseKeepingShares(TransactionHeader header, Money amount, Account account, Category category,
                                                   PaymentMethod method, List<Debt> debts) {
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
        return sharedExpense(header, amount, account, category, method, debts);
    }

    private static Transaction sharedExpense(TransactionHeader header, Money amount, Account account, Category category,
                                             PaymentMethod method, List<Debt> debts) {
        Money total = Money.zero(amount.currency());
        List<Posting> postings = new ArrayList<>();
        for (Debt debt : debts) {
            total = total.add(debt.principal());
            postings.add(new Posting(debt.book(), debt.principal(), header.date()));
        }
        postings.add(0, new Posting(category.book(), amount.subtract(total), header.date()));
        postings.add(new Posting(account.book(), amount.negate(), header.date()));
        return Transaction.builder(header, TransactionType.EXPENSE, amount)
                .paymentMethod(method).account(account.id()).category(category.id())
                .entries(entries(header, postings.toArray(Posting[]::new)))
                .build();
    }

    /** Receita: {@code ACCOUNT +X / INCOME(cat) −X}. O meio de pagamento é obrigatório. */
    public static Transaction income(TransactionHeader header, Money amount, Account account, Category category,
                                     PaymentMethod method) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.INCOME);
        requireCategory(header, category, CategoryKind.INCOME);
        requireMethod(method, TransactionType.INCOME);
        return Transaction.builder(header, TransactionType.INCOME, amount)
                .paymentMethod(method).account(account.id()).category(category.id())
                .entries(entries(header,
                        new Posting(account.book(), amount, header.date()),
                        new Posting(category.book(), amount.negate(), header.date())))
                .build();
    }

    /** Transferência entre contas próprias da mesma liquidez: {@code ACCOUNT(dest) +X / ACCOUNT(orig) −X}. */
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

    /**
     * Aplicação ({@code CONTRIBUTION}: {@code ACCOUNT(invest) +X / ACCOUNT(cash) −X}) ou resgate ({@code REDEMPTION}:
     * sinais invertidos). Uma conta CASH e uma INVESTMENT.
     */
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

    /**
     * Ajuste manual: {@code ACCOUNT ±X / EQUITY ∓X}. Motivo obrigatório; {@code kind} padrão {@code CORRECTION};
     * {@code YIELD} (rendimento, S-26) só em conta de investimento. Não toca consumo nem receita.
     */
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

    /** Ajuste de correção (atalho para o {@code kind} padrão). */
    public static Transaction adjustment(TransactionHeader header, Money amount, Account account, AdjustmentDirection direction,
                                         String reason) {
        return adjustment(header, amount, account, direction, null, reason);
    }

    /**
     * Reembolso em conta: {@code ACCOUNT +X / EXPENSE(cat) −X}, na competência do reembolso (S-10). {@code refundOf} é
     * opcional (S-9); se informado, a categoria é a da compra original e o valor não passa do valor efetivamente
     * cobrado ({@link Transaction#chargedAmount()}: na compra no cartão, só as parcelas não canceladas) menos os
     * reembolsos anteriores.
     */
    public static Transaction refundToAccount(TransactionHeader header, Money amount, Account account, Category category,
                                              RefundOrigin refundOf) {
        requireHeaderAndAmount(header, amount);
        requireCashAccount(header, amount, account, TransactionType.REFUND);
        requireCategory(header, category, CategoryKind.EXPENSE);
        validateRefundOrigin(header, amount, category, refundOf);
        return Transaction.builder(header, TransactionType.REFUND, amount)
                .account(account.id()).category(category.id())
                .refundOf(refundOf == null ? null : refundOf.original().id())
                .entries(entries(header,
                        new Posting(account.book(), amount, header.date()),
                        new Posting(category.book(), amount.negate(), header.date())))
                .build();
    }

    // ------------------------------------------------------------------ validações compartilhadas no pacote

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
        if (!category.id().equals(original.categoryId())) {
            throw new InvalidRefundOriginException("o reembolso volta para a categoria da despesa original.");
        }
        // A1: na compra no cartão, as parcelas canceladas já reverteram o consumo; o teto é o que foi cobrado
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
