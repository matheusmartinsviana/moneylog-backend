package com.moneylog.core.goal.domain;

import java.time.Instant;
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
    private final List<GoalReservation> reservations = new ArrayList<>();

    private Goal(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                 boolean archived) {
        this.id = Objects.requireNonNull(id, "id");
        if (userId == null) {
            throw new GoalFieldRequiredException("userId");
        }
        this.userId = userId;
        this.name = requireName(name);
        this.target = requireTarget(target);
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
        return new Goal(id, userId, name, target, deadline, priority, description, false);
    }

    /** Reidratação: recusa reserva de outra meta e valor zero (dado gravado corrompido vira 500). */
    public static Goal restore(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                               String description, boolean archived, List<GoalReservation> stored) {
        Goal goal = new Goal(id, userId, name, target, deadline, priority, description, archived);
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
        Goal valid = new Goal(id, userId, newName, newTarget, newDeadline, newPriority, newDescription, false);
        this.name = valid.name;
        this.target = valid.target;
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
