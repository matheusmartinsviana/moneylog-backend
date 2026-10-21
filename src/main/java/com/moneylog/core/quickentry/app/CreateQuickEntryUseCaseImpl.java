package com.moneylog.core.quickentry.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.quickentry.app.gateways.QuickEntryAuditGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUsageGateway;
import com.moneylog.core.quickentry.app.gateways.QuickEntryUsageGateway.Usage;
import com.moneylog.core.quickentry.app.gateways.UserTimeZoneGateway;
import com.moneylog.core.quickentry.domain.AmountText;
import com.moneylog.core.quickentry.domain.CategoryMatcher;
import com.moneylog.core.quickentry.domain.QuickEntryAudit;
import com.moneylog.core.quickentry.domain.QuickEntryKind;
import com.moneylog.core.quickentry.domain.QuickEntryMessage;
import com.moneylog.core.quickentry.domain.QuickIdempotencyKey;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickAccountNotAllowedException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickCategoryNotFoundException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickDateOutOfRangeException;
import com.moneylog.core.quickentry.exception.QuickEntryExceptions.QuickLimitReachedException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class CreateQuickEntryUseCaseImpl implements CreateQuickEntryUseCase {

    public static final int MAX_PAST_DAYS = 366;

    private final CreateTransactionUseCase createTransaction;
    private final TransactionGateway transactions;
    private final TransactionDetailsAssembler assembler;
    private final IdempotencyGateway idempotency;
    private final AccountGateway accounts;
    private final CategoryGateway categories;
    private final QuickEntryAuditGateway audits;
    private final UserTimeZoneGateway timeZones;
    private final QuickEntryUsageGateway usage;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final QuickEntryLimits limits;

    @SuppressWarnings("java:S107")
    public CreateQuickEntryUseCaseImpl(CreateTransactionUseCase createTransaction, TransactionGateway transactions,
                                       TransactionDetailsAssembler assembler, IdempotencyGateway idempotency, AccountGateway accounts,
                                       CategoryGateway categories, QuickEntryAuditGateway audits, UserTimeZoneGateway timeZones,
                                       QuickEntryUsageGateway usage, IdGenerator ids, TimeProvider time, QuickEntryLimits limits) {
        this.createTransaction = createTransaction;
        this.transactions = transactions;
        this.assembler = assembler;
        this.idempotency = idempotency;
        this.accounts = accounts;
        this.categories = categories;
        this.audits = audits;
        this.timeZones = timeZones;
        this.usage = usage;
        this.ids = ids;
        this.time = time;
        this.limits = limits;
    }

    @Override
    public CreateQuickEntryResult handle(CreateQuickEntryCommand command) {
        UUID userId = command.userId();
        UUID key = QuickIdempotencyKey.resolve(userId, command.idempotencyKey());
        UUID id = ids.next();
        Instant now = time.now();
        String hash = IdempotentClaim.hash(canonical(command));

        Claim existing = IdempotentClaim.claimOrFind(idempotency, userId, IDEMPOTENCY_SCOPE, key, hash, id, now);
        if (existing != null) {
            IdempotentClaim.requireSameCommand(existing, hash);
            return replay(userId, existing.resourceId());
        }

        Money amount = command.amount().parse();
        ZoneId zone = timeZones.timeZoneOf(userId);
        LocalDate date = resolveDate(command.date(), now, zone);
        requireWithinAmountLimit(amount);
        requireAccountAllowed(userId, command.accountId());
        UUID categoryId = resolveCategory(command);
        enforceDailyLimits(command, amount, now, zone);

        TransactionDetails created = createTransaction.handleClaimed(toTransactionCommand(command, amount, date, categoryId), id,
                TransactionOrigin.quickShortcut());
        audits.insert(new QuickEntryAudit(id, command.tokenId(), userId, command.maskedIp(), now));
        return result(command.kind(), created);
    }

    private CreateQuickEntryResult replay(UUID userId, UUID transactionId) {
        Persisted<Transaction> persisted = transactions.findById(userId, transactionId)
                .orElseThrow(() -> new TransactionNotFoundException(transactionId));
        TransactionDetails details = assembler.assemble(persisted);
        QuickEntryKind kind = details.transaction().type() == TransactionType.INCOME ? QuickEntryKind.INCOME : QuickEntryKind.EXPENSE;
        return result(kind, details);
    }

    private static CreateQuickEntryResult result(QuickEntryKind kind, TransactionDetails details) {
        Transaction transaction = details.transaction();
        String category = details.category() == null ? "Sem categoria" : details.category().name();
        String message = QuickEntryMessage.of(kind, transaction.amount(), category, details.account().name(), transaction.status());
        return new CreateQuickEntryResult(details, message);
    }

    private LocalDate resolveDate(LocalDate requested, Instant now, ZoneId zone) {
        LocalDate today = LocalDate.ofInstant(now, zone);
        if (requested == null) {
            return today;
        }
        if (requested.isAfter(today) || requested.isBefore(today.minusDays(MAX_PAST_DAYS))) {
            throw new QuickDateOutOfRangeException();
        }
        return requested;
    }

    private void requireWithinAmountLimit(Money amount) {
        Money max = limits.maxAmount();
        if (max != null && amount.isGreaterThan(max)) {
            throw new QuickLimitReachedException("O lançamento rápido aceita até " + AmountText.formatBrl(max)
                    + " por vez. Lance este valor pelo app.");
        }
    }

    private void requireAccountAllowed(UUID userId, UUID accountId) {
        Account account = accounts.findById(userId, accountId).map(Persisted::value)
                .orElseThrow(() -> new AccountNotFoundException(accountId));
        if (!account.isActive() || account.liquidity() != LiquidityClass.CASH) {
            throw new QuickAccountNotAllowedException();
        }
    }

    private UUID resolveCategory(CreateQuickEntryCommand command) {
        if (command.category() == null || command.category().isBlank()) {
            return null;
        }
        CategoryKind kind = command.kind() == QuickEntryKind.INCOME ? CategoryKind.INCOME : CategoryKind.EXPENSE;
        List<Category> active = categories.list(command.userId(), kind, false);
        return CategoryMatcher.find(command.category(), active, categories.systemKeysById(command.userId()))
                .map(Category::id).orElseThrow(QuickCategoryNotFoundException::new);
    }

    private void enforceDailyLimits(CreateQuickEntryCommand command, Money amount, Instant now, ZoneId zone) {
        if (!limits.hasDailyLimit()) {
            return;
        }
        usage.lockUser(command.userId());
        LocalDate day = LocalDate.ofInstant(now, zone);
        Usage used = usage.activeUsage(command.userId(), day.atStartOfDay(zone).toInstant(), day.plusDays(1).atStartOfDay(zone).toInstant());
        if (limits.maxEntriesPerDay() > 0 && used.count() + 1 > limits.maxEntriesPerDay()) {
            throw new QuickLimitReachedException("Você chegou a " + limits.maxEntriesPerDay()
                    + " lançamentos rápidos hoje. Use o app para lançar mais.");
        }
        boolean expense = command.kind() == QuickEntryKind.EXPENSE;
        Money cap = expense ? limits.maxExpensePerDay() : limits.maxIncomePerDay();
        Money total = expense ? used.expenseTotal() : used.incomeTotal();
        if (cap != null && total.add(amount).isGreaterThan(cap)) {
            throw new QuickLimitReachedException((expense ? "O limite de gastos do lançamento rápido hoje é " : "O limite de entradas do lançamento rápido hoje é ")
                    + AmountText.formatBrl(cap) + ". Use o app para lançar mais.");
        }
    }

    private static CreateTransactionCommand toTransactionCommand(CreateQuickEntryCommand command, Money amount, LocalDate date,
                                                                 UUID categoryId) {
        Common common = new Common(command.userId(), amount, date, command.description(), null);
        if (command.kind() == QuickEntryKind.INCOME) {
            return new CreateTransactionCommand.Income(common, command.accountId(), categoryId, PaymentMethod.OTHER);
        }
        PaymentMethod method = command.method() == null ? PaymentMethod.OTHER : command.method();
        return new CreateTransactionCommand.Expense(common, command.accountId(), categoryId, method);
    }

    private static String canonical(CreateQuickEntryCommand command) {
        StringBuilder out = new StringBuilder();
        field(out, "kind", command.kind());
        field(out, "amount", command.amount().raw());
        field(out, "category", command.category());
        field(out, "description", command.description());
        field(out, "method", command.method());
        field(out, "date", command.date());
        field(out, "account", command.accountId());
        return out.toString();
    }

    private static void field(StringBuilder out, String name, Object value) {
        String text = value == null ? null : value.toString();
        out.append(name).append('=').append(text == null ? "-" : text.length() + ":" + text).append(';');
    }
}
