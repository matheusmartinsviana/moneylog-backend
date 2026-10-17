package com.moneylog.core.recurrence.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Collection;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.account.domain.Account;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.InvalidRecurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.NotAnOccurrenceException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceEndedException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceInactiveException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceReferenceMismatchException;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceStateInconsistentException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.core.transaction.domain.AccountTransactions;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.ForeignCharge;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.PayrollLoanShare;
import com.moneylog.core.transaction.domain.PayrollTransactions;
import com.moneylog.core.transaction.domain.PurchaseShares;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.SplitMode;
import com.moneylog.core.transaction.domain.TransactionNature;
import com.moneylog.core.transaction.exception.FxExceptions.FxInvalidException;
import com.moneylog.core.transaction.exception.FxExceptions.FxRateRequiredException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNatureInvalidException;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharesInvalidException;

public final class RecurringTransaction {

    public static final int MIN_INCOME_COMPETENCE_OFFSET = -1;
    public static final int MAX_INCOME_COMPETENCE_OFFSET = 1;

    private static final Set<TransactionType> TYPES = Set.of(TransactionType.EXPENSE, TransactionType.INCOME, TransactionType.CARD_PURCHASE);

    private final UUID id;
    private final UUID userId;
    private final TransactionType type;
    private Money amount;
    private String description;
    private UUID categoryId;
    private UUID accountId;
    private UUID cardId;
    private PaymentMethod paymentMethod;
    private int incomeCompetenceOffsetMonths;
    private TransactionNature nature;
    private Schedule schedule;
    private final Set<LocalDate> realized = new HashSet<>();
    private final Set<LocalDate> skipped = new HashSet<>();
    private boolean active = true;
    private PurchaseShares shares;
    private ForeignCharge foreignBasis;
    private SubscriptionTraits traits = SubscriptionTraits.NONE;
    private Provenance provenance = Provenance.DEFAULT;

    private RecurringTransaction(UUID id, UUID userId, TransactionType type, Money amount, String description, UUID categoryId,
                                 UUID accountId, UUID cardId, PaymentMethod paymentMethod, Schedule schedule,
                                 int incomeCompetenceOffsetMonths, TransactionNature nature, PurchaseShares shares) {
        if (id == null || userId == null || schedule == null) {
            throw new InvalidRecurrenceException("id, dono, categoria e agenda são obrigatórios.");
        }
        validateModel(type, amount, description, categoryId, accountId, cardId, paymentMethod, incomeCompetenceOffsetMonths);
        this.id = id;
        this.userId = userId;
        this.type = type;
        this.amount = amount;
        this.description = description;
        this.categoryId = categoryId;
        this.accountId = accountId;
        this.cardId = cardId;
        this.paymentMethod = paymentMethod;
        this.incomeCompetenceOffsetMonths = incomeCompetenceOffsetMonths;
        this.nature = natureFor(type, nature);
        this.schedule = schedule;
        if (shares != null && type != TransactionType.CARD_PURCHASE) {
            throw new SharesInvalidException("o rateio existe só na recorrência de cartão.");
        }
        this.shares = shares;
    }

    private static TransactionNature natureFor(TransactionType type, TransactionNature nature) {
        if (type == TransactionType.EXPENSE) {
            return nature == null ? TransactionNature.FIXED : nature;
        }
        if (nature != null) {
            throw new TransactionNatureInvalidException("só a despesa tem natureza.");
        }
        return null;
    }

    private static void validateModel(TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                      UUID cardId, PaymentMethod paymentMethod, int incomeCompetenceOffsetMonths) {
        if (incomeCompetenceOffsetMonths < MIN_INCOME_COMPETENCE_OFFSET || incomeCompetenceOffsetMonths > MAX_INCOME_COMPETENCE_OFFSET) {
            throw new InvalidRecurrenceException("o deslocamento da competência da renda é 0 (mesmo mês) ou 1 (mês seguinte).");
        }
        if (incomeCompetenceOffsetMonths != 0 && type != TransactionType.INCOME) {
            throw new InvalidRecurrenceException("o deslocamento da competência existe só na recorrência de receita.");
        }
        if (categoryId == null) {
            throw new InvalidRecurrenceException("id, dono, categoria e agenda são obrigatórios.");
        }
        if (type == null || !TYPES.contains(type)) {
            throw new InvalidRecurrenceException("o modelo só pode ser EXPENSE, INCOME ou CARD_PURCHASE à vista.");
        }
        if (amount == null || !amount.isPositive()) {
            throw new InvalidRecurrenceException("o valor precisa ser maior que zero.");
        }
        AmountLimit.ensureWithinLimit(amount, "amount");
        if (description != null && description.strip().length() > TransactionHeader.DESCRIPTION_MAX_LENGTH) {
            throw new InvalidRecurrenceException("a descrição pode ter no máximo " + TransactionHeader.DESCRIPTION_MAX_LENGTH + " caracteres.");
        }
        boolean card = type == TransactionType.CARD_PURCHASE;
        if (card ? cardId == null || accountId != null || paymentMethod != null
                : (accountId == null) != (paymentMethod == null) || cardId != null) {
            throw new InvalidRecurrenceException(card ? "compra no cartão exige só o cartão."
                    : "despesa e receita exigem conta e meio de pagamento juntos (ou nenhum dos dois, conta a definir).");
        }
    }

    public static RecurringTransaction ofAccount(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                                 UUID categoryId, UUID accountId, PaymentMethod method, Schedule schedule) {
        return ofAccount(id, userId, type, amount, description, categoryId, accountId, method, schedule, 0);
    }

    public static RecurringTransaction ofAccount(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                                 UUID categoryId, UUID accountId, PaymentMethod method, Schedule schedule,
                                                 int incomeCompetenceOffsetMonths) {
        return ofAccount(id, userId, type, amount, description, categoryId, accountId, method, schedule, incomeCompetenceOffsetMonths,
                null);
    }

    public static RecurringTransaction ofAccount(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                                 UUID categoryId, UUID accountId, PaymentMethod method, Schedule schedule,
                                                 int incomeCompetenceOffsetMonths, TransactionNature nature) {
        return new RecurringTransaction(id, userId, type, amount, description, categoryId, accountId, null, method, schedule,
                incomeCompetenceOffsetMonths, nature, null);
    }

    public static RecurringTransaction ofCard(UUID id, UUID userId, Money amount, String description, UUID categoryId,
                                              UUID cardId, Schedule schedule) {
        return ofCard(id, userId, amount, description, categoryId, cardId, schedule, null, List.of());
    }

    public static RecurringTransaction ofCard(UUID id, UUID userId, Money amount, String description, UUID categoryId,
                                              UUID cardId, Schedule schedule, SplitMode splitMode, List<ShareLine> shareLines) {
        return new RecurringTransaction(id, userId, TransactionType.CARD_PURCHASE, amount, description, categoryId, null, cardId, null,
                schedule, 0, null, amount == null ? null : PurchaseShares.create(splitMode, shareLines, amount));
    }

    public static RecurringTransaction restore(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                               UUID categoryId, UUID accountId, UUID cardId, PaymentMethod paymentMethod,
                                               Schedule schedule, Collection<LocalDate> realized, Collection<LocalDate> skipped,
                                               boolean active) {
        return restore(id, userId, type, amount, description, categoryId, accountId, cardId, paymentMethod, schedule, realized,
                skipped, active, 0);
    }

    public static RecurringTransaction restore(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                               UUID categoryId, UUID accountId, UUID cardId, PaymentMethod paymentMethod,
                                               Schedule schedule, Collection<LocalDate> realized, Collection<LocalDate> skipped,
                                               boolean active, int incomeCompetenceOffsetMonths) {
        return restore(id, userId, type, amount, description, categoryId, accountId, cardId, paymentMethod, schedule, realized,
                skipped, active, incomeCompetenceOffsetMonths, null);
    }

    public static RecurringTransaction restore(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                               UUID categoryId, UUID accountId, UUID cardId, PaymentMethod paymentMethod,
                                               Schedule schedule, Collection<LocalDate> realized, Collection<LocalDate> skipped,
                                               boolean active, int incomeCompetenceOffsetMonths, TransactionNature nature) {
        return restore(id, userId, type, amount, description, categoryId, accountId, cardId, paymentMethod, schedule, realized,
                skipped, active, incomeCompetenceOffsetMonths, nature, null);
    }

    public static RecurringTransaction restore(UUID id, UUID userId, TransactionType type, Money amount, String description,
                                               UUID categoryId, UUID accountId, UUID cardId, PaymentMethod paymentMethod,
                                               Schedule schedule, Collection<LocalDate> realized, Collection<LocalDate> skipped,
                                               boolean active, int incomeCompetenceOffsetMonths, TransactionNature nature,
                                               PurchaseShares shares) {
        RecurringTransaction recurrence;
        try {
            recurrence = new RecurringTransaction(id, userId, type, amount, description, categoryId, accountId, cardId,
                    paymentMethod, schedule, incomeCompetenceOffsetMonths, nature, shares);
        } catch (InvalidRecurrenceException | SharesInvalidException e) {
            throw new RecurrenceStateInconsistentException(e.getMessage(), e);
        }
        for (LocalDate date : realized) {
            recurrence.restoreDate(date, "realizada", recurrence.realized);
        }
        for (LocalDate date : skipped) {
            recurrence.restoreDate(date, "pulada", recurrence.skipped);
        }
        recurrence.active = active;
        return recurrence;
    }

    private void restoreDate(LocalDate date, String kind, Set<LocalDate> target) {
        if (date == null || !schedule.isOccurrence(date)) {
            throw new RecurrenceStateInconsistentException("a data " + date + " (" + kind + ") não é ocorrência da agenda.");
        }
        if (realized.contains(date) || skipped.contains(date)) {
            throw new RecurrenceStateInconsistentException("a ocorrência de " + date + " aparece como realizada e/ou pulada mais de uma vez.");
        }
        target.add(date);
    }

    public List<LocalDate> realizedDates() {
        return realized.stream().sorted().toList();
    }

    public List<LocalDate> skippedDates() {
        return skipped.stream().sorted().toList();
    }

    public List<Occurrence> occurrences(LocalDate from, LocalDate to) {
        if (!active) {
            return List.of();
        }
        return schedule.datesBetween(from, to).stream()
                .filter(d -> !realized.contains(d) && !skipped.contains(d))
                .map(d -> new Occurrence(id, d, amount, direction(), description))
                .toList();
    }

    public Transaction realize(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, Account account, Category category) {
        return realize(occurrenceDate, transactionId, actualAmount, account, category, null);
    }

    public Transaction realize(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, Account account, Category category,
                               PaymentMethod method) {
        return realize(occurrenceDate, transactionId, actualAmount, account, category, method, null);
    }

    public Transaction realize(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, Account account, Category category,
                               PaymentMethod method, ForeignCharge charge) {
        ensureActive();
        ensurePending(occurrenceDate);
        if (type == TransactionType.CARD_PURCHASE) {
            throw new RecurrenceReferenceMismatchException("conta");
        }
        TransactionHeader header = header(occurrenceDate, transactionId, category);
        PaymentMethod usedMethod = paymentMethod;
        Account validAccount;
        if (accountId == null) {
            if (account == null || method == null) {
                throw new RecurrenceExceptions.AccountRequiredException();
            }
            validAccount = account;
            usedMethod = method;
        } else {
            validAccount = requireAccount(account);
        }
        Money value = chargeValue(actualAmount, charge);
        Transaction transaction = type == TransactionType.EXPENSE
                ? AccountTransactions.expense(header, value, validAccount, category, usedMethod, nature)
                : AccountTransactions.income(header, value, validAccount, category, usedMethod, incomeCompetenceOf(occurrenceDate));
        transaction.attachForeignCharge(charge);
        realized.add(occurrenceDate);
        return transaction;
    }

    public Transaction realizeWithPayrollLoan(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, Account account,
                                              Category category, PaymentMethod method, PayrollLoanShare loan) {
        ensureActive();
        ensurePending(occurrenceDate);
        if (type != TransactionType.INCOME) {
            throw new RecurrenceReferenceMismatchException("receita");
        }
        TransactionHeader header = header(occurrenceDate, transactionId, category);
        PaymentMethod usedMethod = paymentMethod;
        Account validAccount;
        if (accountId == null) {
            if (account == null || method == null) {
                throw new RecurrenceExceptions.AccountRequiredException();
            }
            validAccount = account;
            usedMethod = method;
        } else {
            validAccount = requireAccount(account);
        }
        Money value = actualAmount == null ? amount : actualAmount;
        Transaction transaction = PayrollTransactions.incomeWithLoan(header, value, validAccount, category, usedMethod,
                incomeCompetenceOf(occurrenceDate), loan);
        realized.add(occurrenceDate);
        return transaction;
    }

    public Transaction realizeOnCard(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, CreditCard card,
                                     InvoiceChain chain, LocalDate today, Category category) {
        return realizeOnCard(occurrenceDate, transactionId, actualAmount, card, chain, today, category, null);
    }

    public Transaction realizeOnCard(LocalDate occurrenceDate, UUID transactionId, Money actualAmount, CreditCard card,
                                     InvoiceChain chain, LocalDate today, Category category, ForeignCharge charge) {
        ensureActive();
        ensurePending(occurrenceDate);
        if (type != TransactionType.CARD_PURCHASE) {
            throw new RecurrenceReferenceMismatchException("cartão");
        }
        TransactionHeader header = header(occurrenceDate, transactionId, category);
        CreditCard validCard = requireCard(card);
        Money value = chargeValue(actualAmount, charge);
        Transaction transaction = CardTransactions.purchase(header, value, validCard, category, 1, chain, today,
                shares == null ? null : shares.mode(), shares == null ? List.of() : shares.lines());
        transaction.attachForeignCharge(charge);
        realized.add(occurrenceDate);
        return transaction;
    }

    private Money chargeValue(Money actualAmount, ForeignCharge charge) {
        if (foreignBasis != null && charge == null) {
            throw new FxRateRequiredException();
        }
        if (charge != null) {
            return charge.brl();
        }
        return actualAmount == null ? amount : actualAmount;
    }

    public ForeignCharge chargeFor(Money originalAmount, BigDecimal fxRate, Integer iofBps) {
        if (foreignBasis == null) {
            if (originalAmount != null || fxRate != null || iofBps != null) {
                throw new FxInvalidException("a recorrência está em BRL; câmbio e IOF não se aplicam.");
            }
            return null;
        }
        if (fxRate == null) {
            throw new FxRateRequiredException();
        }
        if (originalAmount != null && !originalAmount.currency().equals(foreignBasis.original().currency())) {
            throw new FxInvalidException("a moeda da cobrança difere da moeda da recorrência.");
        }
        return ForeignCharge.of(originalAmount == null ? foreignBasis.original() : originalAmount, fxRate,
                iofBps == null ? foreignBasis.iofBps() : iofBps);
    }

    public void defineForeignBasis(ForeignCharge basis) {
        if (basis != null && !basis.brl().equals(amount)) {
            throw new FxInvalidException("o valor em BRL diverge do câmbio de referência.");
        }
        if (basis != null && type == TransactionType.INCOME) {
            throw new FxInvalidException("moeda estrangeira não existe na recorrência de receita.");
        }
        this.foreignBasis = basis;
    }

    public void defineProvenance(Provenance newProvenance) {
        this.provenance = Provenance.orDefault(newProvenance);
    }

    public void defineSubscriptionTraits(SubscriptionTraits newTraits) {
        SubscriptionTraits valid = newTraits == null ? SubscriptionTraits.NONE : newTraits;
        if (type == TransactionType.INCOME && (valid.subscription() || valid.scope() != RecurrenceScope.PERSONAL)) {
            throw new InvalidRecurrenceException("assinatura e escopo existem só na recorrência de despesa.");
        }
        this.traits = valid;
    }

    private Competence incomeCompetenceOf(LocalDate occurrenceDate) {
        return incomeCompetenceOffsetMonths == 0 ? null : Competence.from(occurrenceDate).plusMonths(incomeCompetenceOffsetMonths);
    }

    private TransactionHeader header(LocalDate occurrenceDate, UUID transactionId, Category category) {
        if (category == null || !category.id().equals(categoryId)) {
            throw new RecurrenceReferenceMismatchException("categoria");
        }
        return new TransactionHeader(transactionId, userId, occurrenceDate, description, null,
                TransactionOrigin.recurrence(id, occurrenceDate));
    }

    public void skip(LocalDate occurrenceDate) {
        ensureActive();
        ensurePending(occurrenceDate);
        skipped.add(occurrenceDate);
    }

    public void markRealized(LocalDate occurrenceDate) {
        ensurePending(occurrenceDate);
        realized.add(occurrenceDate);
    }

    public void deactivate() {
        active = false;
    }

    public void updateDetails(Money newAmount, String newDescription, UUID newCategoryId, UUID newAccountId, PaymentMethod newMethod,
                              UUID newCardId) {
        updateDetails(newAmount, newDescription, newCategoryId, newAccountId, newMethod, newCardId, incomeCompetenceOffsetMonths);
    }

    public void updateDetails(Money newAmount, String newDescription, UUID newCategoryId, UUID newAccountId, PaymentMethod newMethod,
                              UUID newCardId, int newIncomeCompetenceOffsetMonths) {
        updateDetails(newAmount, newDescription, newCategoryId, newAccountId, newMethod, newCardId, newIncomeCompetenceOffsetMonths,
                nature);
    }

    public void updateDetails(Money newAmount, String newDescription, UUID newCategoryId, UUID newAccountId, PaymentMethod newMethod,
                              UUID newCardId, int newIncomeCompetenceOffsetMonths, TransactionNature newNature) {
        TransactionNature validNature = natureFor(type, newNature == null ? nature : newNature);
        validateModel(type, newAmount, newDescription, newCategoryId, newAccountId, newCardId, newMethod, newIncomeCompetenceOffsetMonths);
        this.incomeCompetenceOffsetMonths = newIncomeCompetenceOffsetMonths;
        this.nature = validNature;
        this.amount = newAmount;
        this.description = newDescription;
        this.categoryId = newCategoryId;
        this.accountId = newAccountId;
        this.cardId = newCardId;
        this.paymentMethod = newMethod;
        if (shares != null) {
            this.shares = PurchaseShares.create(shares.mode(), shares.lines(), newAmount);
        }
    }

    public void changeShares(SplitMode splitMode, List<ShareLine> shareLines) {
        PurchaseShares updated = PurchaseShares.create(splitMode, shareLines, amount);
        if (updated != null && type != TransactionType.CARD_PURCHASE) {
            throw new SharesInvalidException("o rateio existe só na recorrência de cartão.");
        }
        this.shares = updated;
    }

    public void endOn(LocalDate end) {
        if (end == null) {
            throw new InvalidRecurrenceException("a data de encerramento é obrigatória.");
        }
        changeEnd(end);
    }

    public void changeEnd(LocalDate end) {
        if (end != null) {
            for (LocalDate date : realized) {
                requireNotBefore(end, date, "realizada");
            }
            for (LocalDate date : skipped) {
                requireNotBefore(end, date, "pulada");
            }
        }
        this.schedule = new Schedule(schedule.frequency(), schedule.interval(), schedule.unit(), schedule.startDate(), end,
                schedule.maxOccurrences());
    }

    private static void requireNotBefore(LocalDate end, LocalDate date, String kind) {
        if (end.isBefore(date)) {
            throw new InvalidRecurrenceException("o fim (" + end + ") não pode ser anterior à ocorrência " + kind + " de " + date + ".");
        }
    }

    public FlowDirection direction() {
        return type == TransactionType.INCOME ? FlowDirection.IN : FlowDirection.OUT;
    }

    private void ensureActive() {
        if (!active) {
            throw new RecurrenceInactiveException();
        }
    }

    public boolean isEnded(LocalDate today) {
        return !schedule.hasOccurrenceOnOrAfter(today);
    }

    public void ensureEndRemovalDoesNotReopen(LocalDate today) {
        if (isEnded(today) && schedule.endDate() != null && schedule.until(null).hasOccurrenceOnOrAfter(today)) {
            throw new RecurrenceEndedException();
        }
    }

    private void ensurePending(LocalDate date) {
        if (date == null || !schedule.isOccurrence(date)) {
            throw new NotAnOccurrenceException(date);
        }
        if (realized.contains(date)) {
            throw new OccurrenceAlreadyRealizedException(date);
        }
        if (skipped.contains(date)) {
            throw new OccurrenceSkippedException(date);
        }
    }

    private Account requireAccount(Account account) {
        if (account == null || !account.id().equals(accountId)) {
            throw new RecurrenceReferenceMismatchException("conta");
        }
        return account;
    }

    private CreditCard requireCard(CreditCard card) {
        if (card == null || !card.id().equals(cardId)) {
            throw new RecurrenceReferenceMismatchException("cartão");
        }
        return card;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public TransactionType type() {
        return type;
    }

    public Money amount() {
        return amount;
    }

    public String description() {
        return description;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public UUID accountId() {
        return accountId;
    }

    public UUID cardId() {
        return cardId;
    }

    public int incomeCompetenceOffsetMonths() {
        return incomeCompetenceOffsetMonths;
    }

    public PaymentMethod paymentMethod() {
        return paymentMethod;
    }

    public TransactionNature nature() {
        return nature;
    }

    public Schedule schedule() {
        return schedule;
    }

    public PurchaseShares purchaseShares() {
        return shares;
    }

    public ForeignCharge foreignBasis() {
        return foreignBasis;
    }

    public Provenance provenance() {
        return provenance;
    }

    public SubscriptionTraits traits() {
        return traits;
    }

    public boolean isSubscription() {
        return traits.subscription();
    }

    public boolean isActive() {
        return active;
    }
}
