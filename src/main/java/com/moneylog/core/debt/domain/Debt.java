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

/**
 * Dívida (D1 §2.6): "me devem" ({@code RECEIVABLE}) ou "eu devo" ({@code PAYABLE}).
 *
 * <ul>
 *   <li>{@code WITH_MOVEMENT}: nasce junto com a sua única movimentação de origem ({@code LOAN_GIVEN} ou captação);
 *       os postings são da movimentação (S-41).</li>
 *   <li>{@code WITHOUT_MOVEMENT}: reconhecida contra {@code EQUITY} (origem {@code DEBT_RECOGNITION}), sem caixa nem
 *       consumo (PR 01 caso 8). Uma dívida {@code PAYABLE} pode nascer de consumo (D1 §2.6.1, S-137): com categoria, o
 *       reconhecimento é {@code EXPENSE(cat) +X / PAYABLE −X} (consumo +X, caixa 0).</li>
 *   <li>O status é derivado das quitações ativas; só o encerramento ({@code CANCELED}/{@code VOIDED}) é gravado.</li>
 *   <li>Perdão ({@link #cancel}) zera o saldo contra {@code EQUITY} (origem {@code DEBT_CANCELLATION}), sem
 *       despesa nem receita (S-19).</li>
 * </ul>
 */
public final class Debt {

    public static final String DEBT_ALREADY_HAS_ORIGIN = "DEBT_ALREADY_HAS_ORIGIN";
    public static final int TEXT_MAX_LENGTH = 500;
    /** Coluna {@code debts.description} (VARCHAR(200)). */
    public static final int DESCRIPTION_MAX_LENGTH = 200;
    /** Coluna {@code debts.creditor_name} (VARCHAR(100)). */
    public static final int CREDITOR_NAME_MAX_LENGTH = 100;

    private final UUID id;
    private final UUID userId;
    private final UUID personId;
    /** Credor livre das "outras dívidas" (P1, ADR-013): banco, loja, financiamento; só em {@code PAYABLE}, no lugar da pessoa. */
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

    /** Reidratação (veja {@link #restore}); copia o estado já validado. */
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
    }

    /**
     * Dívida que nasce com a movimentação de origem (uso de {@code DebtTransactions}: o principal é o valor da origem).
     */
    public static Debt withMovement(UUID id, UUID userId, Person person, DebtDirection direction, Money principal,
                                    LocalDate date, LocalDate expectedDate, String description, UUID originTransactionId) {
        return new Debt(id, userId, person, null, direction, DebtOrigin.WITH_MOVEMENT, principal, description, date, expectedDate,
                required(originTransactionId, "originTransactionId"), null);
    }

    /** Dívida sem movimentação ("eu devo 200 ao Pedro"): {@code PAYABLE −X / EQUITY +X} na data da dívida. */
    public static Debt withoutMovement(UUID id, UUID userId, Person person, DebtDirection direction, Money principal,
                                       LocalDate date, LocalDate expectedDate, String description) {
        return new Debt(id, userId, person, null, direction, DebtOrigin.WITHOUT_MOVEMENT, principal, description, date, expectedDate,
                null, null);
    }

    /**
     * "Outra dívida" (P1, ADR-013): {@code PAYABLE} com credor livre (banco, loja, financiamento), sem {@link Person}. Reconhecida contra
     * {@code EQUITY} como a dívida com pessoa ({@code PAYABLE −X / EQUITY +X}): sem caixa e sem consumo. Quitação e perdão são os de sempre.
     */
    public static Debt withoutMovementToCreditor(UUID id, UUID userId, String creditorName, Money principal, LocalDate date,
                                                 LocalDate expectedDate, String description) {
        return new Debt(id, userId, null, creditorName, DebtDirection.PAYABLE, DebtOrigin.WITHOUT_MOVEMENT, principal, description, date,
                expectedDate, null, null);
    }

    /**
     * Dívida {@code PAYABLE} sem movimentação que nasce de consumo (D1 §2.6.1, S-137: "um amigo pagou meu jantar de 80"):
     * {@code EXPENSE(cat) +X / PAYABLE −X} na data da dívida. Consumo +X na competência da data, caixa 0, patrimônio −X. A
     * categoria é de despesa, do usuário e ativa; dívida {@code RECEIVABLE} não tem categoria.
     */
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

    /**
     * Reidrata uma dívida já persistida (usado pelo mapper da infra). Não consulta relógio nem refaz as operações:
     * devolve o agregado como foi gravado, com status e saldo <b>derivados</b> das quitações (S-37). Estado corrompido
     * (principal inválido, origem sem movimentação, encerramento incompleto, lançamento de outra origem, grupo que não
     * fecha em zero, Σ quitações ativas &gt; principal) lança {@link DebtStateInconsistentException}.
     *
     * @param settlements quitações, ativas e inativas ({@link #settlements()})
     * @param entries     lançamentos próprios (reconhecimento e perdão), ativos e inativos ({@link #entries()})
     */
    public static Debt restore(DebtState state, List<DebtSettlement> settlements, List<LedgerEntry> entries) {
        try {
            return validated(state, settlements, entries);
        } catch (DebtStateInconsistentException e) {
            throw e;
        } catch (DomainException e) {
            throw new DebtStateInconsistentException(e.getMessage(), e);
        }
    }

    /** Estado persistido (o inverso de {@link #restore}); quitações e lançamentos saem por {@link #settlements()} e {@link #entries()}. */
    public DebtState state() {
        return new DebtState(id, userId, personId, direction, origin, principal, description, date, expectedDate,
                originTransactionId, closure, closureDate, closureReason, categoryId, creditorName);
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

    // ------------------------------------------------------------------ status derivado

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

    /** Σ quitações ativas. */
    public Money settled() {
        return Money.sum(settlements.stream().filter(DebtSettlement::active).map(DebtSettlement::amount).toList(), principal.currency());
    }

    /** Saldo devedor: principal − quitações ativas (0 se encerrada). */
    public Money outstanding() {
        return closure != null ? Money.zero(principal.currency()) : principal.subtract(settled());
    }

    /** "Atrasada": previsão anterior a hoje e saldo &gt; 0 (D1 §2.6). */
    public boolean isOverdue(LocalDate today) {
        return expectedDate != null && expectedDate.isBefore(today) && outstanding().isPositive();
    }

    // ------------------------------------------------------------------ operações

    public void ensureDirection(DebtDirection expected) {
        if (direction != expected) {
            throw new DebtDirectionMismatchException(expected, direction);
        }
    }

    /**
     * Uma dívida existente nunca recebe outra origem (S-41): emprestar de novo = nova dívida. Devolve a recusa para o
     * chamador lançar.
     */
    public DebtOperationRefusedException newOriginRefusal() {
        return new DebtOperationRefusedException(DEBT_ALREADY_HAS_ORIGIN, origin == DebtOrigin.WITH_MOVEMENT
                ? "a dívida já tem movimentação de origem; emprestar de novo cria outra dívida."
                : "a dívida foi registrada sem movimentação e não recebe origem.");
    }

    /** Valida uma quitação sem registrá-la (o chamador cria a movimentação e depois registra). */
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

    /**
     * Valida, sem mudar nada, o efeito do estorno da movimentação {@code transactionId} nesta dívida (M1): origem só
     * sem quitações ativas e sem encerramento; quitação só se existir ativa e a dívida não foi perdoada. O ponto único
     * de estorno chama isto <b>antes</b> de estornar a movimentação, para não deixar estado parcial.
     */
    public void ensureCanVoidTransaction(UUID transactionId) {
        required(transactionId, "transactionId");
        if (transactionId.equals(originTransactionId)) {
            refuseIfNotAllowed(DebtVoidRules.forOrigin(this));
        } else {
            refuseIfNotAllowed(DebtVoidRules.forSettlement(this));
            activeSettlementIndex(transactionId);
        }
    }

    /** Estorno de uma quitação: o status volta sozinho (é derivado). Recusado depois do perdão (DEBT_CLOSED). */
    public void voidSettlement(UUID transactionId) {
        refuseIfNotAllowed(DebtVoidRules.forSettlement(this));
        int index = activeSettlementIndex(transactionId);
        settlements.set(index, settlements.get(index).deactivate());
    }

    /**
     * Edita a descrição e a previsão (o principal, a pessoa e a origem não mudam). Dívida encerrada não muda
     * ({@code DEBT_CLOSED}).
     */
    public void updateDetails(String newDescription, LocalDate newExpectedDate) {
        ensureNotClosed();
        this.description = requireDescription(newDescription);
        this.expectedDate = FinancialDates.requireInWindow(newExpectedDate, "expectedDate");
    }

    /** Perdão (S-19): só {@code OPEN}/{@code PARTIALLY_SETTLED}; zera o saldo contra {@code EQUITY}. */
    public void cancel(String reason, LocalDate today) {
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

    /** Estorno do registro de uma dívida sem movimentação: só sem quitações ativas; os postings ficam inativos. */
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

    /** Efeito do estorno da movimentação de origem (a guarda já foi consultada pela movimentação). */
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

    // ------------------------------------------------------------------ leitura

    /** Postings próprios da dívida (reconhecimento e perdão), ativos e inativos. */
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

    /** Pessoa devedora/credora; {@code null} na dívida com credor livre ({@link #creditorName()}). */
    public UUID personId() {
        return personId;
    }

    /** Credor livre das "outras dívidas"; {@code null} quando há pessoa. */
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

    /** Categoria de despesa da dívida {@code PAYABLE} que nasceu de consumo (S-137); {@code null} nas demais. */
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

    // ------------------------------------------------------------------ internos

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
