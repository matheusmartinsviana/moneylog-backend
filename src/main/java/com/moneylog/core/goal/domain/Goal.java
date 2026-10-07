package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.goal.exception.GoalExceptions.GoalAlreadyArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNameRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNameTooLongException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReleaseExceedsReservedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTargetNotPositiveException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTextTooLongException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.time.FinancialDates;

/**
 * Meta (D1 §2.9, S-147 a S-150, S-156): objetivo com alvo, prazo opcional, prioridade e <b>reservas lógicas</b> sobre contas.
 * A reserva não é movimentação: nenhum lançamento, nenhum livro; caixa, consumo, receita e patrimônio não mudam.
 *
 * <ul>
 *   <li>{@code current(asOf)} = Σ reservas com data ≤ {@code asOf} (reserva soma, liberação subtrai).</li>
 *   <li>{@code ACHIEVED} é <b>derivado</b> ({@code current ≥ target}); só o arquivamento é gravado.</li>
 *   <li>Meta arquivada não conta no "reservado" (o dinheiro volta a ficar disponível) e não aceita reserva nem edição.</li>
 *   <li>Liberar mais do que a meta tem reservado naquela conta, em qualquer data, é recusado.</li>
 * </ul>
 */
public final class Goal {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int DESCRIPTION_MAX_LENGTH = 500;

    private final UUID id;
    private final UUID userId;
    private String name;
    private Money target;
    private LocalDate deadline;
    private GoalPriority priority;
    private String description;
    private boolean archived;
    private final GoalKind kind;
    /** Total de dívidas fixado em "cadastrei tudo" (só {@code DEBT_PAYOFF}); {@code null} enquanto a meta está "montando". */
    private Money baseline;
    private Instant baselineAt;
    private final List<GoalReservation> reservations = new ArrayList<>();

    private Goal(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                 boolean archived, GoalKind kind, Money baseline, Instant baselineAt) {
        this.id = Objects.requireNonNull(id, "id");
        if (userId == null) {
            throw new GoalFieldRequiredException("userId");
        }
        this.userId = userId;
        this.name = requireName(name);
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind == GoalKind.DEBT_PAYOFF) {
            this.target = baseline == null ? Money.zero(CurrencyCode.BRL) : requireTarget(baseline);
            if ((baseline == null) != (baselineAt == null) || (baseline != null && !baseline.equals(target))) {
                throw new GoalStateInconsistentException("baseline, baselineAt e alvo da meta de quitar dívidas não batem.");
            }
            this.baseline = baseline;
            this.baselineAt = baselineAt;
        } else {
            if (baseline != null || baselineAt != null) {
                throw new GoalStateInconsistentException("só a meta de quitar dívidas tem baseline.");
            }
            this.target = requireTarget(target);
        }
        this.deadline = FinancialDates.requireInWindow(deadline, "deadline");
        if (priority == null) {
            throw new GoalFieldRequiredException("priority");
        }
        this.priority = priority;
        this.description = requireDescription(description);
        this.archived = archived;
    }

    public static Goal create(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                              String description) {
        return new Goal(id, userId, name, target, deadline, priority, description, false, GoalKind.SAVINGS, null, null);
    }

    /**
     * Meta "quitar todas as dívidas" (ADR-013): sem alvo e sem reservas; fica "montando" (sem baseline) até {@link #anchorBaseline}. O
     * progresso e o restante vêm do total de dívidas, que o chamador informa.
     */
    public static Goal createDebtPayoff(UUID id, UUID userId, String name, LocalDate deadline, GoalPriority priority, String description) {
        return new Goal(id, userId, name, null, deadline, priority, description, false, GoalKind.DEBT_PAYOFF, null, null);
    }

    /** Reidratação: recusa reserva de outra meta e valor zero (dado gravado corrompido vira 500). */
    public static Goal restore(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                               String description, boolean archived, List<GoalReservation> stored) {
        return restore(id, userId, name, target, deadline, priority, description, archived, GoalKind.SAVINGS, null, null, stored);
    }

    /** Reidratação com o tipo e a baseline (meta de quitar dívidas). */
    @SuppressWarnings("java:S107")
    public static Goal restore(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                               String description, boolean archived, GoalKind kind, Money baseline, Instant baselineAt,
                               List<GoalReservation> stored) {
        Goal goal = new Goal(id, userId, name, kind == GoalKind.DEBT_PAYOFF ? baseline : target, deadline, priority, description, archived,
                kind, baseline, baselineAt);
        for (GoalReservation reservation : stored) {
            if (!id.equals(reservation.goalId())) {
                throw new GoalStateInconsistentException("reserva " + reservation.id() + " de outra meta.");
            }
            goal.reservations.add(reservation);
        }
        return goal;
    }

    // ------------------------------------------------------------------ comandos

    /** Edita os dados da meta (não as reservas). Meta arquivada não é editada. */
    public void update(String newName, Money newTarget, LocalDate newDeadline, GoalPriority newPriority, String newDescription) {
        ensureNotArchived();
        Goal valid = new Goal(id, userId, newName, kind == GoalKind.DEBT_PAYOFF ? baseline : newTarget, newDeadline, newPriority,
                newDescription, false, kind, baseline, baselineAt);
        this.name = valid.name;
        this.target = valid.target; // na meta de quitar dívidas o alvo é a baseline: o campo do corpo é ignorado
        this.deadline = valid.deadline;
        this.priority = valid.priority;
        this.description = valid.description;
    }

    /**
     * Reserva (valor positivo) ou libera (negativo) dinheiro numa conta, sem tocar o razão. A liberação não pode passar do que a
     * meta tem reservado naquela conta: o saldo corrente por data nunca fica negativo.
     *
     * @return a reserva criada
     */
    public GoalReservation reserve(UUID reservationId, ReserveAllocation allocation, LocalDate date, String note, Instant now) {
        ensureNotArchived();
        if (kind == GoalKind.DEBT_PAYOFF) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalKindNotReservableException();
        }
        GoalReservation reservation = new GoalReservation(reservationId, id, allocation.accountId(), allocation.amount(), date, note, now);
        if (reservation.isRelease()) {
            ensureReleaseCovered(reservation);
        }
        reservations.add(reservation);
        return reservation;
    }

    public void archive() {
        if (archived) {
            throw new GoalAlreadyArchivedException();
        }
        this.archived = true;
    }

    public void unarchive() {
        if (!archived) {
            throw new GoalNotArchivedException();
        }
        this.archived = false;
    }

    private void ensureReleaseCovered(GoalReservation release) {
        List<GoalReservation> ofAccount = new ArrayList<>(reservations.stream().filter(r -> r.accountId().equals(release.accountId())).toList());
        ofAccount.add(release);
        // no mesmo dia as reservas contam antes das liberações (a ordem de gravação não é confiável com o mesmo instante)
        ofAccount.sort(Comparator.comparing(GoalReservation::date).thenComparing(r -> r.isRelease() ? 1 : 0).thenComparing(GoalReservation::createdAt));
        Money running = Money.zero(CurrencyCode.BRL);
        for (GoalReservation r : ofAccount) {
            running = running.add(r.amount());
            if (running.isNegative()) {
                throw new GoalReleaseExceedsReservedException(netOn(release.accountId()), release.amount());
            }
        }
    }

    private void ensureNotArchived() {
        if (archived) {
            throw new GoalArchivedException();
        }
    }

    // ------------------------------------------------------------------ cálculos

    /** Σ reservas (e liberações) com data ≤ {@code asOf}. */
    public Money current(LocalDate asOf) {
        return Money.sum(reservations.stream().filter(r -> !r.date().isAfter(asOf)).map(GoalReservation::amount).toList(), CurrencyCode.BRL);
    }

    public GoalProgress progress(LocalDate asOf) {
        return GoalProgress.of(current(asOf), target);
    }

    /** Derivado: {@code current ≥ target} (S-150). */
    public boolean isAchieved(LocalDate asOf) {
        return !current(asOf).isLessThan(target);
    }

    public GoalStatus status(LocalDate asOf) {
        return archived ? GoalStatus.ARCHIVED : isAchieved(asOf) ? GoalStatus.ACHIEVED : GoalStatus.ACTIVE;
    }

    /**
     * Estimativa do valor por mês para atingir a meta no prazo (S-156): {@code (alvo − atual) / meses}, com os meses-calendário do
     * mês de {@code today} ao mês do prazo, ambos inclusive, arredondado <b>para cima</b> ao centavo. Meta atingida → 0,00; sem prazo
     * ou prazo vencido → {@code null}.
     */
    public Money monthlyNeeded(LocalDate today) {
        if (isAchieved(today)) {
            return Money.zero(CurrencyCode.BRL);
        }
        if (deadline == null || deadline.isBefore(today)) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(deadline)) + 1;
        long missing = target.subtract(current(today)).cents();
        return Money.ofCents(Math.ceilDiv(missing, months), CurrencyCode.BRL);
    }

    // ------------------------------------------------------------------ quitar dívidas (ADR-013)

    public GoalKind kind() {
        return kind;
    }

    public boolean isDebtPayoff() {
        return kind == GoalKind.DEBT_PAYOFF;
    }

    /** "Cadastrei tudo": a baseline está fixada. Antes disso a meta está "montando" e não tem percentual. */
    public boolean isBaselineSet() {
        return baseline != null;
    }

    /** Total de dívidas fixado em "cadastrei tudo"; {@code null} enquanto "montando". */
    public Money baseline() {
        return baseline;
    }

    public Instant baselineAt() {
        return baselineAt;
    }

    /**
     * Fixa (ou re-ancora, P8) a baseline com o total atual de dívidas. Total zero é recusado: não há o que quitar e a meta nunca vira
     * "conquistada" sem ter tido dívida. O alvo passa a ser a baseline.
     */
    public void anchorBaseline(Money totalDebt, Instant now) {
        if (kind != GoalKind.DEBT_PAYOFF) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalKindMismatchException(GoalKind.DEBT_PAYOFF.name());
        }
        ensureNotArchived();
        if (totalDebt == null || !totalDebt.isPositive()) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.DebtPayoffNothingToBaselineException();
        }
        this.baseline = AmountLimit.ensureWithinLimit(totalDebt, "baseline");
        this.baselineAt = Objects.requireNonNull(now, "now");
        this.target = this.baseline;
    }

    /**
     * Progresso da meta de quitar dívidas: {@code current = max(0, baseline − total)}, {@code ratio = current / baseline} (0..1, nunca
     * negativo quando a dívida cresce). Sem baseline: tudo zero e razão {@code 0.0000}.
     */
    public GoalProgress payoffProgress(Money totalDebt) {
        Money zero = Money.zero(CurrencyCode.BRL);
        if (baseline == null) {
            return new GoalProgress(zero, zero, BigDecimal.ZERO.setScale(GoalProgress.RATIO_SCALE));
        }
        Money paid = baseline.subtract(totalDebt);
        return GoalProgress.of(paid.isNegative() ? zero : paid, baseline);
    }

    /** O total de dívidas passou da baseline: apareceu dívida nova depois de "cadastrei tudo" (nada é re-ancorado sozinho). */
    public boolean grewSinceBaseline(Money totalDebt) {
        return baseline != null && totalDebt.isGreaterThan(baseline);
    }

    /** {@code ACHIEVED} só com baseline e total zerado; total zero sem baseline nunca é conquista. */
    public GoalStatus payoffStatus(Money totalDebt) {
        if (archived) {
            return GoalStatus.ARCHIVED;
        }
        return baseline != null && totalDebt.isZero() ? GoalStatus.ACHIEVED : GoalStatus.ACTIVE;
    }

    /** Estimativa por mês para quitar o restante até o prazo (como S-156); sem prazo ou prazo vencido, {@code null}. */
    public Money payoffMonthlyNeeded(Money totalDebt, LocalDate today) {
        if (baseline != null && totalDebt.isZero()) {
            return Money.zero(CurrencyCode.BRL);
        }
        if (deadline == null || deadline.isBefore(today)) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(deadline)) + 1;
        return Money.ofCents(Math.ceilDiv(totalDebt.cents(), months), CurrencyCode.BRL);
    }

    /** Reservado líquido da meta na conta até {@code asOf}; meta arquivada não reserva nada. */
    public Money reservedOn(UUID accountId, LocalDate asOf) {
        return archived ? Money.zero(CurrencyCode.BRL) : netOn(accountId, asOf);
    }

    /** Contas em que a meta tem reserva líquida positiva até {@code asOf} (nenhuma, se arquivada), na ordem da primeira reserva. */
    public List<UUID> reservedAccounts(LocalDate asOf) {
        if (archived) {
            return List.of();
        }
        Map<UUID, Money> net = new LinkedHashMap<>();
        for (GoalReservation r : reservations) {
            if (!r.date().isAfter(asOf)) {
                net.merge(r.accountId(), r.amount(), Money::add);
            }
        }
        return net.entrySet().stream().filter(e -> e.getValue().isPositive()).map(Map.Entry::getKey).toList();
    }

    /** "Reserva descoberta" (S-148): alguma conta com reserva desta meta está descoberta (reservado total > saldo). Só sinaliza. */
    public boolean isUncovered(Predicate<UUID> accountUncovered, LocalDate asOf) {
        return reservedAccounts(asOf).stream().anyMatch(accountUncovered);
    }

    private Money netOn(UUID accountId) {
        return netOn(accountId, LocalDate.MAX);
    }

    private Money netOn(UUID accountId, LocalDate asOf) {
        return Money.sum(reservations.stream().filter(r -> r.accountId().equals(accountId) && !r.date().isAfter(asOf))
                .map(GoalReservation::amount).toList(), CurrencyCode.BRL);
    }

    /** Garante que a meta é do solicitante; de outro usuário é tratada como inexistente. */
    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new GoalNotFoundException(id);
        }
    }

    // ------------------------------------------------------------------ leitura

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String name() {
        return name;
    }

    public Money target() {
        return target;
    }

    /** Pode ser {@code null}. */
    public LocalDate deadline() {
        return deadline;
    }

    public GoalPriority priority() {
        return priority;
    }

    /** Pode ser {@code null}. */
    public String description() {
        return description;
    }

    public boolean isArchived() {
        return archived;
    }

    /** Todas as reservas, na ordem em que foram acrescentadas. */
    public List<GoalReservation> reservations() {
        return List.copyOf(reservations);
    }

    // ------------------------------------------------------------------ validação

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new GoalNameRequiredException();
        }
        String stripped = value.strip();
        PlainText.requireClean(stripped, "name", false);
        if (stripped.length() > NAME_MAX_LENGTH) {
            throw new GoalNameTooLongException(NAME_MAX_LENGTH);
        }
        return stripped;
    }

    private static Money requireTarget(Money value) {
        if (value == null) {
            throw new GoalFieldRequiredException("target");
        }
        if (!value.isPositive()) {
            throw new GoalTargetNotPositiveException();
        }
        if (!CurrencyCode.BRL.equals(value.currency())) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAccountNotEligibleException("só BRL no MVP.");
        }
        return AmountLimit.ensureWithinLimit(value, "target");
    }

    private static String requireDescription(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        PlainText.requireClean(stripped, "description", true);
        if (stripped.length() > DESCRIPTION_MAX_LENGTH) {
            throw new GoalTextTooLongException("description", DESCRIPTION_MAX_LENGTH);
        }
        return stripped;
    }

    @Override
    public boolean equals(Object o) {
        return o instanceof Goal other && id.equals(other.id);
    }

    @Override
    public int hashCode() {
        return id.hashCode();
    }

    @Override
    public String toString() {
        return "Goal[" + id + ", " + name + ", " + target + "]";
    }
}
