package com.moneylog.core.debt.domain;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Objects;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.debt.exception.DebtExceptions.DebtCategoryNotAllowedException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtDescriptionTooLongException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtDirectionMismatchException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtFieldRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtOperationRefusedException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtReasonRequiredException;
import com.moneylog.core.debt.exception.DebtExceptions.DebtStateInconsistentException;
import com.moneylog.core.debt.exception.DebtExceptions.InvalidDebtPrincipalException;
import com.moneylog.core.debt.exception.DebtExceptions.SettlementExceedsOutstandingException;
import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.ledger.BookRef;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.Posting;
import com.moneylog.core.sk.ledger.PostingSource;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.ledger.VoidDecision;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.FinancialDates;

public final class Debt {

    public static final String DEBT_ALREADY_HAS_ORIGIN = "DEBT_ALREADY_HAS_ORIGIN";
    public static final int TEXT_MAX_LENGTH = 500;
    public static final int DESCRIPTION_MAX_LENGTH = 200;
    public static final int CREDITOR_NAME_MAX_LENGTH = 100;
    public static final int CHANNEL_MAX_LENGTH = 60;

    private final UUID id;
    private final UUID userId;
    private final UUID personId;
    private final String creditorName;
    private final DebtDirection direction;
    private final DebtOrigin origin;
    private final Money principal;
    private final UUID categoryId;
    private final LocalDate date;
    private final UUID originTransactionId;
    private String description;
    private LocalDate expectedDate;
    private final List<DebtSettlement> settlements = new ArrayList<>();
    private List<LedgerEntry> entries;
    private DebtClosure closure;
    private LocalDate closureDate;
    private String closureReason;
    private DebtKind kind = DebtKind.PERSONAL;
    private String channel;

    private Debt(UUID id, UUID userId, Person person, String creditorName, DebtDirection direction, DebtOrigin origin, Money principal,
                 String description, LocalDate date, LocalDate expectedDate, UUID originTransactionId, UUID categoryId) {
        this.id = required(id, "id");
        this.userId = required(userId, "userId");
        if (person == null && creditorName == null) {
            required(person, "person");
        }
        if (person == null) {
            this.creditorName = requireCreditor(creditorName);
            if (direction != DebtDirection.PAYABLE) {
                throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException(
                        "o credor livre só existe em dívida que você deve (PAYABLE).");
            }
            this.personId = null;
        } else {
            if (creditorName != null) {
                throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException(
                        "informe a pessoa ou o nome do credor, não os dois.");
            }
            person.ensureBelongsTo(userId);
            person.ensureCanReceiveDebts();
            this.creditorName = null;
            this.personId = person.id();
        }
        this.direction = required(direction, "direction");
        this.origin = origin;
        if (principal == null || !principal.isPositive() || !CurrencyCode.BRL.equals(principal.currency())) {
            throw new InvalidDebtPrincipalException();
        }
        this.principal = AmountLimit.ensureWithinLimit(principal, "principal");
        this.description = requireDescription(description);
        this.date = FinancialDates.requireInWindow(required(date, "date"), "date");
        this.expectedDate = FinancialDates.requireInWindow(expectedDate, "expectedDate");
        this.originTransactionId = originTransactionId;
        this.categoryId = categoryId;
        this.entries = origin == DebtOrigin.WITHOUT_MOVEMENT ? recognitionEntries() : List.of();
    }

    private Debt(DebtState s, List<DebtSettlement> settlements, List<LedgerEntry> entries) {
        this.id = s.id();
        this.userId = s.userId();
        this.personId = s.personId();
        this.creditorName = s.creditorName();
        this.direction = s.direction();
        this.origin = s.origin();
        this.principal = s.principal();
        this.description = s.description();
        this.date = s.date();
        this.expectedDate = s.expectedDate();
        this.originTransactionId = s.originTransactionId();
        this.categoryId = s.categoryId();
        this.settlements.addAll(settlements);
        this.entries = List.copyOf(entries);
        this.closure = s.closure();
        this.closureDate = s.closureDate();
        this.closureReason = s.closureReason();
        this.kind = s.kind();
        this.channel = s.channel();
    }

    public static Debt withMovement(UUID id, UUID userId, Person person, DebtDirection direction, Money principal,
                                    LocalDate date, LocalDate expectedDate, String description, UUID originTransactionId) {
        return new Debt(id, userId, person, null, direction, DebtOrigin.WITH_MOVEMENT, principal, description, date, expectedDate,
                required(originTransactionId, "originTransactionId"), null);
    }

    public static Debt withoutMovement(UUID id, UUID userId, Person person, DebtDirection direction, Money principal,
                                       LocalDate date, LocalDate expectedDate, String description) {
        return new Debt(id, userId, person, null, direction, DebtOrigin.WITHOUT_MOVEMENT, principal, description, date, expectedDate,
                null, null);
    }

    public static Debt cardCashOutForMe(UUID id, UUID userId, String cardName, Money principal, LocalDate date, String description,
                                        UUID originTransactionId, String channel) {
        Debt debt = new Debt(id, userId, null, cardName, DebtDirection.PAYABLE, DebtOrigin.WITH_MOVEMENT, principal, description, date,
                null, required(originTransactionId, "originTransactionId"), null);
        debt.kind = DebtKind.CARD_CASH_OUT;
        debt.channel = requireChannel(channel);
        return debt;
    }

    public static Debt cardCashOutForPerson(UUID id, UUID userId, Person person, Money principal, LocalDate date, String description,
                                            UUID originTransactionId, String channel) {
        Debt debt = new Debt(id, userId, required(person, "person"), null, DebtDirection.RECEIVABLE, DebtOrigin.WITH_MOVEMENT,
                principal, description, date, null, required(originTransactionId, "originTransactionId"), null);
        debt.kind = DebtKind.CARD_CASH_OUT;
        debt.channel = requireChannel(channel);
        return debt;
    }

    public static Debt withoutMovementToCreditor(UUID id, UUID userId, String creditorName, Money principal, LocalDate date,
                                                 LocalDate expectedDate, String description) {
        return new Debt(id, userId, null, creditorName, DebtDirection.PAYABLE, DebtOrigin.WITHOUT_MOVEMENT, principal, description, date,
                expectedDate, null, null);
    }

    public static Debt withoutMovement(UUID id, UUID userId, Person person, DebtDirection direction, Money principal,
                                       LocalDate date, LocalDate expectedDate, String description, Category category) {
        if (category == null) {
            return withoutMovement(id, userId, person, direction, principal, date, expectedDate, description);
        }
        if (direction != DebtDirection.PAYABLE) {
            throw new DebtCategoryNotAllowedException();
        }
        category.ensureBelongsTo(userId);
        category.ensureCanReceiveTransactions();
        category.ensureKind(CategoryKind.EXPENSE);
        return new Debt(id, userId, person, null, direction, DebtOrigin.WITHOUT_MOVEMENT, principal, description, date, expectedDate,
                null, category.id());
    }

    public static Debt restore(DebtState state, List<DebtSettlement> settlements, List<LedgerEntry> entries) {
        try {
            return validated(state, settlements, entries);
        } catch (DebtStateInconsistentException e) {
            throw e;
        } catch (DomainException e) {
            throw new DebtStateInconsistentException(e.getMessage(), e);
        }
    }

    public DebtState state() {
        return new DebtState(id, userId, personId, direction, origin, principal, description, date, expectedDate,
                originTransactionId, closure, closureDate, closureReason, categoryId, creditorName, kind, channel);
    }

    private static Debt validated(DebtState s, List<DebtSettlement> settlements, List<LedgerEntry> entries) {
        Objects.requireNonNull(s, "state");
        Objects.requireNonNull(settlements, "settlements");
        Objects.requireNonNull(entries, "entries");
        if (s.id() == null || s.userId() == null || s.direction() == null || s.origin() == null || s.date() == null) {
            throw inconsistent("id, dono, direção, origem e data são obrigatórios.");
        }
        if ((s.personId() == null) == (s.creditorName() == null || s.creditorName().isBlank())) {
            throw inconsistent("id, dono, pessoa ou credor livre (exatamente um), direção, origem e data são obrigatórios.");
        }
        if (s.personId() == null && s.direction() != DebtDirection.PAYABLE) {
            throw inconsistent("o credor livre só existe em dívida PAYABLE.");
        }
        if (s.principal() == null || !s.principal().isPositive() || !CurrencyCode.BRL.equals(s.principal().currency())) {
            throw inconsistent("principal inválido: " + s.principal() + ".");
        }
        if (s.kind() == DebtKind.CARD_CASH_OUT && s.origin() != DebtOrigin.WITH_MOVEMENT) {
            throw inconsistent("o Pix no crédito nasce de uma movimentação.");
        }
        AmountLimit.ensureWithinLimit(s.principal(), "principal");
        if ((s.origin() == DebtOrigin.WITH_MOVEMENT) != (s.originTransactionId() != null)) {
            throw inconsistent("a movimentação de origem só existe (e é obrigatória) em WITH_MOVEMENT.");
        }
        if (s.categoryId() != null && (s.direction() != DebtDirection.PAYABLE || s.origin() != DebtOrigin.WITHOUT_MOVEMENT)) {
            throw inconsistent("a categoria só existe na dívida PAYABLE sem movimentação.");
        }
        boolean closed = s.closure() != null;
        if (closed != (s.closureDate() != null) || closed != (s.closureReason() != null)) {
            throw inconsistent("encerramento exige data e motivo, e só existe com encerramento.");
        }
        List<LedgerEntry> recognition = new ArrayList<>();
        List<LedgerEntry> cancellation = new ArrayList<>();
        for (LedgerEntry entry : entries) {
            boolean own = s.id().equals(entry.source().sourceId());
            PostingSourceType type = entry.source().type();
            if (!own || type != PostingSourceType.DEBT_RECOGNITION && type != PostingSourceType.DEBT_CANCELLATION) {
                throw inconsistent("lançamento de outra origem: " + entry.source() + ".");
            }
            (type == PostingSourceType.DEBT_RECOGNITION ? recognition : cancellation).add(entry);
        }
        if (s.categoryId() != null && !recognition.isEmpty()
                && recognition.stream().noneMatch(e -> e.posting().book().equals(BookRef.expense(s.categoryId())))) {
            throw inconsistent("o reconhecimento da dívida com categoria precisa lançar na despesa dela.");
        }
        ensureZeroSum(recognition, "reconhecimento");
        ensureZeroSum(cancellation, "perdão");
        if ((s.origin() == DebtOrigin.WITH_MOVEMENT) != recognition.isEmpty()) {
            throw inconsistent("só a dívida sem movimentação tem lançamento de reconhecimento próprio.");
        }
        if ((s.closure() == DebtClosure.CANCELED) != !cancellation.isEmpty()) {
            throw inconsistent("o lançamento de perdão só existe (e é obrigatório) na dívida perdoada.");
        }
        boolean voided = s.closure() == DebtClosure.VOIDED;
        if (!recognition.isEmpty() && recognition.stream().anyMatch(LedgerEntry::active) == voided) {
            throw inconsistent(voided ? "dívida estornada com reconhecimento ativo." : "dívida com reconhecimento inativo.");
        }
        Money settled = Money.zero(s.principal().currency());
        Set<UUID> activeSettlements = new HashSet<>();
        for (DebtSettlement settlement : settlements) {
            if (settlement.active()) {
                if (!activeSettlements.add(settlement.transactionId())) {
                    throw inconsistent("quitação ativa repetida da movimentação " + settlement.transactionId() + ".");
                }
                settled = settled.add(settlement.amount());
            }
            if (settlement.transactionId().equals(s.originTransactionId())) {
                throw inconsistent("a movimentação de origem não pode ser uma quitação.");
            }
        }
        if (settled.isGreaterThan(s.principal())) {
            throw inconsistent("Σ das quitações ativas (" + settled + ") passa do principal (" + s.principal() + ").");
        }
        return new Debt(s, settlements, entries);
    }

    private static void ensureZeroSum(List<LedgerEntry> group, String name) {
        if (!group.isEmpty() && !Money.sum(group.stream().map(e -> e.posting().amount()).toList(), CurrencyCode.BRL).isZero()) {
            throw inconsistent("os lançamentos de " + name + " não fecham em zero.");
        }
    }

    private static DebtStateInconsistentException inconsistent(String reason) {
        return new DebtStateInconsistentException(reason);
    }

    public DebtStatus status() {
        if (closure != null) {
            return closure == DebtClosure.CANCELED ? DebtStatus.CANCELED : DebtStatus.VOIDED;
        }
        Money settled = settled();
        if (settled.isZero()) {
            return DebtStatus.OPEN;
        }
        return settled.isLessThan(principal) ? DebtStatus.PARTIALLY_SETTLED : DebtStatus.SETTLED;
    }

    public Money settled() {
        return Money.sum(settlements.stream().filter(DebtSettlement::active).map(DebtSettlement::amount).toList(), principal.currency());
    }

    public boolean carriedByCard() {
        return kind == DebtKind.CARD_CASH_OUT && direction == DebtDirection.PAYABLE;
    }

    public boolean deductedInPayroll() {
        return kind == DebtKind.PAYROLL && direction == DebtDirection.PAYABLE;
    }

    public Money outstanding() {
        return closure != null || carriedByCard() ? Money.zero(principal.currency()) : principal.subtract(settled());
    }

    public boolean isOverdue(LocalDate today) {
        return !deductedInPayroll() && !carriedByCard() && expectedDate != null && expectedDate.isBefore(today) && outstanding().isPositive();
    }

    public boolean unmarkPayroll() {
        if (kind != DebtKind.PAYROLL) {
            return false;
        }
        kind = DebtKind.PERSONAL;
        return true;
    }

    public boolean markPayroll() {
        ensureDirection(DebtDirection.PAYABLE);
        ensureNotClosed();
        if (kind == DebtKind.PAYROLL) {
            return false;
        }
        if (kind == DebtKind.CARD_CASH_OUT) {
            throw new DebtOperationRefusedException("DEBT_KIND_NOT_PAYROLL", "o Pix no crédito não é consignado em folha.");
        }
        kind = DebtKind.PAYROLL;
        return true;
    }

    public void ensureDirection(DebtDirection expected) {
        if (direction != expected) {
            throw new DebtDirectionMismatchException(expected, direction);
        }
    }

    public DebtOperationRefusedException newOriginRefusal() {
        return new DebtOperationRefusedException(DEBT_ALREADY_HAS_ORIGIN, origin == DebtOrigin.WITH_MOVEMENT
                ? "a dívida já tem movimentação de origem; emprestar de novo cria outra dívida."
                : "a dívida foi registrada sem movimentação e não recebe origem.");
    }

    public void ensureCanSettle(Money amount) {
        ensureNotClosed();
        if (amount.isGreaterThan(outstanding())) {
            throw new SettlementExceedsOutstandingException();
        }
    }

    public void registerSettlement(UUID transactionId, Money amount) {
        required(transactionId, "transactionId");
        ensureCanSettle(amount);
        settlements.add(new DebtSettlement(transactionId, amount, true));
    }

    public void ensureCanVoidTransaction(UUID transactionId) {
        required(transactionId, "transactionId");
        if (transactionId.equals(originTransactionId)) {
            refuseIfNotAllowed(DebtVoidRules.forOrigin(this));
        } else {
            refuseIfNotAllowed(DebtVoidRules.forSettlement(this));
            activeSettlementIndex(transactionId);
        }
    }

    public void voidSettlement(UUID transactionId) {
        refuseIfNotAllowed(DebtVoidRules.forSettlement(this));
        int index = activeSettlementIndex(transactionId);
        settlements.set(index, settlements.get(index).deactivate());
    }

    public void updateDetails(String newDescription, LocalDate newExpectedDate) {
        ensureNotClosed();
        this.description = requireDescription(newDescription);
        this.expectedDate = FinancialDates.requireInWindow(newExpectedDate, "expectedDate");
    }

    public void cancel(String reason, LocalDate today) {
        if (carriedByCard()) {
            throw new DebtOperationRefusedException("DEBT_HAS_ORIGIN_TRANSACTION",
                    "a dívida do Pix no crédito vive na fatura do cartão; estorne a movimentação de origem.");
        }
        String validReason = requireReason(reason);
        required(today, "today");
        ensureNotClosed();
        if (status() == DebtStatus.SETTLED) {
            throw new DebtOperationRefusedException("DEBT_ALREADY_SETTLED", "a dívida já está quitada.");
        }
        Money remaining = outstanding();
        Posting debtSide = new Posting(book(), remaining.negate().times(direction == DebtDirection.RECEIVABLE ? 1 : -1), today);
        List<LedgerEntry> cancellation = LedgerEntry.activeGroup(PostingSource.debtCancellation(id),
                List.of(debtSide, new Posting(BookRef.equity(), debtSide.amount().negate(), today)));
        List<LedgerEntry> all = new ArrayList<>(entries);
        all.addAll(cancellation);
        this.entries = List.copyOf(all);
        close(DebtClosure.CANCELED, today, validReason);
    }

    public void voidRecord(String reason, LocalDate today) {
        String validReason = requireReason(reason);
        required(today, "today");
        if (origin == DebtOrigin.WITH_MOVEMENT) {
            throw new DebtOperationRefusedException("DEBT_HAS_ORIGIN_TRANSACTION",
                    "a dívida nasceu de uma movimentação; estorne a movimentação de origem.");
        }
        refuseIfNotAllowed(DebtVoidRules.forOrigin(this));
        this.entries = entries.stream().map(LedgerEntry::deactivate).toList();
        close(DebtClosure.VOIDED, today, validReason);
    }

    public void markOriginVoided(UUID transactionId, String reason, LocalDate today) {
        if (!transactionId.equals(originTransactionId)) {
            throw new DebtNotFoundException(id);
        }
        refuseIfNotAllowed(DebtVoidRules.forOrigin(this));
        close(DebtClosure.VOIDED, required(today, "today"), requireReason(reason));
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new DebtNotFoundException(id);
        }
    }

    public BookRef book() {
        return direction == DebtDirection.RECEIVABLE ? BookRef.receivable(id) : BookRef.payable(id);
    }

    public List<LedgerEntry> entries() {
        return entries;
    }

    public List<DebtSettlement> settlements() {
        return List.copyOf(settlements);
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public UUID personId() {
        return personId;
    }

    public String creditorName() {
        return creditorName;
    }

    public DebtDirection direction() {
        return direction;
    }

    public DebtOrigin origin() {
        return origin;
    }

    public Money principal() {
        return principal;
    }

    public String description() {
        return description;
    }

    public LocalDate date() {
        return date;
    }

    public LocalDate expectedDate() {
        return expectedDate;
    }

    public UUID originTransactionId() {
        return originTransactionId;
    }

    public UUID categoryId() {
        return categoryId;
    }

    public DebtClosure closure() {
        return closure;
    }

    public LocalDate closureDate() {
        return closureDate;
    }

    public String closureReason() {
        return closureReason;
    }

    public DebtKind kind() {
        return kind;
    }

    public String channel() {
        return channel;
    }

    private static String requireChannel(String channel) {
        if (channel == null || channel.isBlank()) {
            return null;
        }
        String trimmed = channel.strip();
        if (trimmed.length() > CHANNEL_MAX_LENGTH) {
            throw new DebtDescriptionTooLongException(CHANNEL_MAX_LENGTH);
        }
        return trimmed;
    }

    private List<LedgerEntry> recognitionEntries() {
        if (categoryId != null) {
            return LedgerEntry.activeGroup(PostingSource.debtRecognition(id), List.of(
                    new Posting(BookRef.expense(categoryId), principal, date), new Posting(book(), principal.negate(), date)));
        }
        Money debtSide = direction == DebtDirection.RECEIVABLE ? principal : principal.negate();
        return LedgerEntry.activeGroup(PostingSource.debtRecognition(id), List.of(
                new Posting(book(), debtSide, date), new Posting(BookRef.equity(), debtSide.negate(), date)));
    }

    private void ensureNotClosed() {
        if (closure != null) {
            throw new DebtOperationRefusedException(DebtVoidRules.DEBT_CLOSED, "a dívida está encerrada (" + closure + ").");
        }
    }

    private int activeSettlementIndex(UUID transactionId) {
        for (int i = 0; i < settlements.size(); i++) {
            if (settlements.get(i).transactionId().equals(transactionId) && settlements.get(i).active()) {
                return i;
            }
        }
        throw new DebtNotFoundException(id);
    }

    private void close(DebtClosure newClosure, LocalDate today, String reason) {
        this.closure = newClosure;
        this.closureDate = today;
        this.closureReason = reason;
    }

    private static void refuseIfNotAllowed(VoidDecision decision) {
        if (!decision.allowed()) {
            throw new DebtOperationRefusedException(decision.code(), decision.reason());
        }
    }

    private static String requireDescription(String description) {
        if (description == null || description.isBlank()) {
            return null;
        }
        String trimmed = description.strip();
        if (trimmed.length() > DESCRIPTION_MAX_LENGTH) {
            throw new DebtDescriptionTooLongException(DESCRIPTION_MAX_LENGTH);
        }
        return trimmed;
    }

    private static String requireCreditor(String name) {
        if (name == null || name.isBlank()) {
            throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException("informe a pessoa ou o nome do credor.");
        }
        String trimmed = name.strip();
        if (trimmed.length() > CREDITOR_NAME_MAX_LENGTH) {
            throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException(
                    "o nome do credor pode ter no máximo " + CREDITOR_NAME_MAX_LENGTH + " caracteres.");
        }
        return trimmed;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new DebtReasonRequiredException();
        }
        String trimmed = reason.strip();
        if (trimmed.length() > TEXT_MAX_LENGTH) {
            throw new DebtOperationRefusedException("DEBT_REASON_TOO_LONG", "o motivo pode ter no máximo " + TEXT_MAX_LENGTH + " caracteres.");
        }
        return trimmed;
    }

    private static <T> T required(T value, String field) {
        if (value == null) {
            throw new DebtFieldRequiredException(field);
        }
        return value;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Debt other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Debt[" + id + ", " + direction + ", " + principal + ", " + status() + "]";
    }
}
