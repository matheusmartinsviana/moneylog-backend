package com.moneylog.core.goal.domain;

import java.time.Instant;
import java.math.BigDecimal;
import java.time.LocalDate;
import java.time.YearMonth;
import java.time.ZoneId;
import java.time.temporal.ChronoUnit;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.UUID;
import java.util.function.Predicate;

import com.moneylog.core.goal.exception.GoalExceptions.GoalActionLimitException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalActionNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalAlreadyArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFieldRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricTargetEqualsBaselineException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalMetricTargetNegativeException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNameRequiredException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNameTooLongException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotArchivedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalFeaturedKindNotSupportedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoLimitReachedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotoNotFoundException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotosKindNotSupportedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalPhotosOrderMismatchException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalReleaseExceedsReservedException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalStateInconsistentException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTargetNotPositiveException;
import com.moneylog.core.goal.exception.GoalExceptions.GoalTextTooLongException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.text.PlainText;
import com.moneylog.core.sk.time.FinancialDates;

public final class Goal {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int DESCRIPTION_MAX_LENGTH = 500;
    public static final int PLAN_NOTE_MAX_LENGTH = 500;
    public static final int MAX_ACTIONS = 10;

    private final UUID id;
    private final UUID userId;
    private String name;
    private Money target;
    private LocalDate deadline;
    private GoalPriority priority;
    private String description;
    private boolean archived;
    private final GoalKind kind;
    private Money baseline;
    private Instant baselineAt;
    private final GoalMetric metric;
    private GoalDirection direction;
    private final List<GoalReservation> reservations = new ArrayList<>();
    private String planNote;
    private final List<GoalAction> actions = new ArrayList<>();
    private boolean featured;
    private final List<GoalPhoto> photos = new ArrayList<>();

    @SuppressWarnings("java:S107")
    private Goal(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority, String description,
                 boolean archived, GoalKind kind, Money baseline, Instant baselineAt, GoalMetric metric, GoalDirection direction) {
        this.id = Objects.requireNonNull(id, "id");
        if (userId == null) {
            throw new GoalFieldRequiredException("userId");
        }
        this.userId = userId;
        this.kind = Objects.requireNonNull(kind, "kind");
        if (kind != GoalKind.METRIC && (metric != null || direction != null)) {
            throw new GoalStateInconsistentException("só a meta de medida tem metric e direction.");
        }
        this.metric = metric;
        if (kind == GoalKind.METRIC) {
            this.name = name == null || name.isBlank() ? null : requireName(name);
            if (metric == null) {
                throw new GoalFieldRequiredException("metric");
            }
            if (baseline == null || baselineAt == null) {
                throw new GoalStateInconsistentException("a meta de medida sempre tem baseline e baselineAt.");
            }
            this.target = requireMetricTarget(target);
            this.baseline = AmountLimit.ensureWithinLimit(baseline, "baseline");
            this.baselineAt = baselineAt;
            GoalDirection derived = GoalDirection.derive(this.target, this.baseline);
            if (derived == null) {
                throw new GoalMetricTargetEqualsBaselineException();
            }
            if (direction != null && direction != derived) {
                throw new GoalStateInconsistentException("direction não bate com alvo e baseline.");
            }
            this.direction = derived;
        } else if (kind == GoalKind.DEBT_PAYOFF) {
            this.name = requireName(name);
            this.target = baseline == null ? Money.zero(CurrencyCode.BRL) : requireTarget(baseline);
            if ((baseline == null) != (baselineAt == null) || (baseline != null && !baseline.equals(target))) {
                throw new GoalStateInconsistentException("baseline, baselineAt e alvo da meta de quitar dívidas não batem.");
            }
            this.baseline = baseline;
            this.baselineAt = baselineAt;
        } else {
            this.name = requireName(name);
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
        return new Goal(id, userId, name, target, deadline, priority, description, false, GoalKind.SAVINGS, null, null, null, null);
    }

    public static Goal createDebtPayoff(UUID id, UUID userId, String name, LocalDate deadline, GoalPriority priority, String description) {
        return new Goal(id, userId, name, null, deadline, priority, description, false, GoalKind.DEBT_PAYOFF, null, null, null, null);
    }

    @SuppressWarnings("java:S107")
    public static Goal createMetric(UUID id, UUID userId, String name, GoalMetric metric, Money target, Money baseline, Instant now,
                                    LocalDate deadline, GoalPriority priority, String description) {
        return new Goal(id, userId, name, target, deadline, priority, description, false, GoalKind.METRIC, baseline,
                Objects.requireNonNull(now, "now"), metric, null);
    }

    public static Goal restore(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                               String description, boolean archived, List<GoalReservation> stored) {
        return restore(id, userId, name, target, deadline, priority, description, archived, GoalKind.SAVINGS, null, null, stored);
    }

    @SuppressWarnings("java:S107")
    public static Goal restore(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                               String description, boolean archived, GoalKind kind, Money baseline, Instant baselineAt,
                               List<GoalReservation> stored) {
        return restore(id, userId, name, target, deadline, priority, description, archived, kind, baseline, baselineAt, null, null, stored);
    }

    @SuppressWarnings("java:S107")
    public static Goal restore(UUID id, UUID userId, String name, Money target, LocalDate deadline, GoalPriority priority,
                               String description, boolean archived, GoalKind kind, Money baseline, Instant baselineAt, GoalMetric metric,
                               GoalDirection direction, List<GoalReservation> stored) {
        Goal goal = new Goal(id, userId, name, kind == GoalKind.DEBT_PAYOFF ? baseline : target, deadline, priority, description, archived,
                kind, baseline, baselineAt, metric, direction);
        for (GoalReservation reservation : stored) {
            if (kind == GoalKind.METRIC) {
                throw new GoalStateInconsistentException("a meta de medida não tem reservas.");
            }
            if (!id.equals(reservation.goalId())) {
                throw new GoalStateInconsistentException("reserva " + reservation.id() + " de outra meta.");
            }
            goal.reservations.add(reservation);
        }
        return goal;
    }

    public void update(String newName, Money newTarget, LocalDate newDeadline, GoalPriority newPriority, String newDescription) {
        ensureNotArchived();
        Goal valid = new Goal(id, userId, newName, kind == GoalKind.DEBT_PAYOFF ? baseline : newTarget, newDeadline, newPriority,
                newDescription, false, kind, baseline, baselineAt, metric, null);
        this.name = valid.name;
        this.direction = valid.direction;
        this.target = valid.target;
        this.deadline = valid.deadline;
        this.priority = valid.priority;
        this.description = valid.description;
    }

    public GoalReservation reserve(UUID reservationId, ReserveAllocation allocation, LocalDate date, String note, Instant now) {
        ensureNotArchived();
        if (kind != GoalKind.SAVINGS) {
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
        this.featured = false;
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

    public Money current(LocalDate asOf) {
        return Money.sum(reservations.stream().filter(r -> !r.date().isAfter(asOf)).map(GoalReservation::amount).toList(), CurrencyCode.BRL);
    }

    public GoalProgress progress(LocalDate asOf) {
        return GoalProgress.of(current(asOf), target);
    }

    public boolean isAchieved(LocalDate asOf) {
        return !current(asOf).isLessThan(target);
    }

    public GoalStatus status(LocalDate asOf) {
        return archived ? GoalStatus.ARCHIVED : isAchieved(asOf) ? GoalStatus.ACHIEVED : GoalStatus.ACTIVE;
    }

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

    public GoalKind kind() {
        return kind;
    }

    public boolean isDebtPayoff() {
        return kind == GoalKind.DEBT_PAYOFF;
    }

    public boolean isBaselineSet() {
        return baseline != null;
    }

    public Money baseline() {
        return baseline;
    }

    public Instant baselineAt() {
        return baselineAt;
    }

    public void anchorBaseline(Money current, Instant now) {
        if (kind == GoalKind.SAVINGS) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalKindMismatchException("DEBT_PAYOFF ou METRIC");
        }
        ensureNotArchived();
        Objects.requireNonNull(now, "now");
        if (kind == GoalKind.METRIC) {
            Money bounded = AmountLimit.ensureWithinLimit(Objects.requireNonNull(current, "current"), "baseline");
            GoalDirection derived = GoalDirection.derive(target, bounded);
            if (derived == null) {
                throw new GoalMetricTargetEqualsBaselineException();
            }
            this.baseline = bounded;
            this.baselineAt = now;
            this.direction = derived;
            return;
        }
        if (current == null || !current.isPositive()) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.DebtPayoffNothingToBaselineException();
        }
        this.baseline = AmountLimit.ensureWithinLimit(current, "baseline");
        this.baselineAt = now;
        this.target = this.baseline;
    }

    public boolean isMetric() {
        return kind == GoalKind.METRIC;
    }

    public GoalMetric metric() {
        return metric;
    }

    public GoalDirection direction() {
        return direction;
    }

    public MetricProgress metricProgress(Money current) {
        if (kind != GoalKind.METRIC) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalKindMismatchException(GoalKind.METRIC.name());
        }
        boolean decrease = direction == GoalDirection.DECREASE;
        boolean reached = decrease ? !current.isGreaterThan(target) : !current.isLessThan(target);
        Money remaining = reached ? Money.zero(CurrencyCode.BRL) : decrease ? current.subtract(target) : target.subtract(current);
        long done = decrease ? baseline.cents() - current.cents() : current.cents() - baseline.cents();
        long span = Math.abs(target.cents() - baseline.cents());
        BigDecimal ratio;
        if (reached) {
            ratio = BigDecimal.ONE.setScale(GoalProgress.RATIO_SCALE);
        } else if (done <= 0) {
            ratio = BigDecimal.ZERO.setScale(GoalProgress.RATIO_SCALE);
        } else {
            ratio = BigDecimal.valueOf(done).divide(BigDecimal.valueOf(span), GoalProgress.RATIO_SCALE, java.math.RoundingMode.HALF_UP);
            BigDecimal almost = new BigDecimal("0.9999");
            if (ratio.compareTo(almost) > 0) {
                ratio = almost;
            }
        }
        return new MetricProgress(current, remaining, ratio, reached);
    }

    public GoalStatus metricStatus(MetricProgress progress) {
        return archived ? GoalStatus.ARCHIVED : progress.reached() ? GoalStatus.ACHIEVED : GoalStatus.ACTIVE;
    }

    public Money metricMonthlyNeeded(MetricProgress progress, LocalDate today) {
        if (progress.reached()) {
            return Money.zero(CurrencyCode.BRL);
        }
        if (deadline == null || deadline.isBefore(today)) {
            return null;
        }
        long months = ChronoUnit.MONTHS.between(YearMonth.from(today), YearMonth.from(deadline)) + 1;
        return Money.ofCents(Math.ceilDiv(progress.remaining().cents(), months), CurrencyCode.BRL);
    }

    public GoalProgress payoffProgress(Money totalDebt) {
        Money zero = Money.zero(CurrencyCode.BRL);
        if (baseline == null) {
            return new GoalProgress(zero, zero, BigDecimal.ZERO.setScale(GoalProgress.RATIO_SCALE));
        }
        Money paid = baseline.subtract(totalDebt);
        return GoalProgress.of(paid.isNegative() ? zero : paid, baseline);
    }

    public boolean grewSinceBaseline(Money totalDebt) {
        return baseline != null && totalDebt.isGreaterThan(baseline);
    }

    public GoalStatus payoffStatus(Money totalDebt) {
        if (archived) {
            return GoalStatus.ARCHIVED;
        }
        return baseline != null && totalDebt.isZero() ? GoalStatus.ACHIEVED : GoalStatus.ACTIVE;
    }

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

    public boolean isFeatured() {
        return featured;
    }

    public List<GoalPhoto> photos() {
        return List.copyOf(photos);
    }

    public UUID coverPhotoId() {
        return photos.isEmpty() ? null : photos.getFirst().id();
    }

    public Goal restoreMedia(boolean storedFeatured, List<GoalPhoto> storedPhotos) {
        if ((storedFeatured || !storedPhotos.isEmpty()) && kind != GoalKind.SAVINGS) {
            throw new GoalStateInconsistentException("só a meta de guardar dinheiro tem fotos e destaque.");
        }
        if (storedFeatured && archived) {
            throw new GoalStateInconsistentException("meta arquivada em destaque.");
        }
        this.featured = storedFeatured;
        this.photos.clear();
        this.photos.addAll(storedPhotos);
        return this;
    }

    public void feature() {
        if (kind != GoalKind.SAVINGS) {
            throw new GoalFeaturedKindNotSupportedException();
        }
        ensureNotArchived();
        this.featured = true;
    }

    public void unfeature() {
        this.featured = false;
    }

    public GoalPhoto addPhoto(GoalPhoto photo, int maxPerGoal) {
        ensureCanAddPhoto(maxPerGoal);
        photos.add(photo);
        return photo;
    }

    public void ensureCanAddPhoto(int maxPerGoal) {
        ensurePhotosAllowed();
        if (photos.size() >= maxPerGoal) {
            throw new GoalPhotoLimitReachedException(maxPerGoal);
        }
    }

    public GoalPhoto removePhoto(UUID photoId) {
        ensurePhotosAllowed();
        return photos.remove(indexOfPhoto(photoId));
    }

    public void setCover(UUID photoId) {
        ensurePhotosAllowed();
        photos.addFirst(photos.remove(indexOfPhoto(photoId)));
    }

    public void reorderPhotos(List<UUID> orderedIds) {
        ensurePhotosAllowed();
        if (orderedIds == null || orderedIds.size() != photos.size() || new java.util.HashSet<>(orderedIds).size() != photos.size()) {
            throw new GoalPhotosOrderMismatchException();
        }
        Map<UUID, GoalPhoto> byId = new LinkedHashMap<>();
        photos.forEach(p -> byId.put(p.id(), p));
        List<GoalPhoto> reordered = new ArrayList<>();
        for (UUID id : orderedIds) {
            GoalPhoto photo = byId.get(id);
            if (photo == null) {
                throw new GoalPhotosOrderMismatchException();
            }
            reordered.add(photo);
        }
        photos.clear();
        photos.addAll(reordered);
    }

    private void ensurePhotosAllowed() {
        if (kind != GoalKind.SAVINGS) {
            throw new GoalPhotosKindNotSupportedException();
        }
        ensureNotArchived();
    }

    private int indexOfPhoto(UUID photoId) {
        for (int i = 0; i < photos.size(); i++) {
            if (photos.get(i).id().equals(photoId)) {
                return i;
            }
        }
        throw new GoalPhotoNotFoundException(photoId);
    }

    public String planNote() {
        return planNote;
    }

    public List<GoalAction> actions() {
        return List.copyOf(actions);
    }

    public boolean hasDeclaredPlan() {
        return planNote != null || !actions.isEmpty();
    }

    public Goal restorePlan(String storedNote, List<GoalAction> storedActions) {
        this.planNote = requirePlanNote(storedNote);
        this.actions.clear();
        this.actions.addAll(storedActions);
        return this;
    }

    public void declarePlanNote(String note) {
        ensureNotArchived();
        this.planNote = requirePlanNote(note);
    }

    public GoalAction addAction(UUID actionId, String title, GoalActionFrequency frequency, Instant now) {
        ensureNotArchived();
        if (actions.size() >= MAX_ACTIONS) {
            throw new GoalActionLimitException(MAX_ACTIONS);
        }
        GoalAction action = GoalAction.create(actionId, title, frequency, now);
        actions.add(action);
        return action;
    }

    public GoalAction updateAction(UUID actionId, String title, GoalActionFrequency frequency) {
        ensureNotArchived();
        int index = indexOfAction(actionId);
        GoalAction edited = actions.get(index).edit(title, frequency);
        actions.set(index, edited);
        return edited;
    }

    public void removeAction(UUID actionId) {
        ensureNotArchived();
        actions.remove(indexOfAction(actionId));
    }

    public GoalAction checkIn(UUID actionId, String period, boolean done) {
        ensureNotArchived();
        int index = indexOfAction(actionId);
        GoalAction updated = actions.get(index).checkIn(period, done);
        actions.set(index, updated);
        return updated;
    }

    private int indexOfAction(UUID actionId) {
        for (int i = 0; i < actions.size(); i++) {
            if (actions.get(i).id().equals(actionId)) {
                return i;
            }
        }
        throw new GoalActionNotFoundException(actionId);
    }

    public GoalPlanner.Track track(LocalDate createdOn, ZoneId zone) {
        return switch (kind) {
            case SAVINGS -> new GoalPlanner.Track(false, Money.zero(CurrencyCode.BRL), createdOn, target, deadline);
            case METRIC -> new GoalPlanner.Track(direction == GoalDirection.DECREASE, baseline, baselineAt.atZone(zone).toLocalDate(), target,
                    deadline);
            case DEBT_PAYOFF -> baseline == null ? null
                    : new GoalPlanner.Track(true, baseline, baselineAt.atZone(zone).toLocalDate(), Money.zero(CurrencyCode.BRL), deadline);
        };
    }

    public boolean hasAutoPlan() {
        return deadline != null && (kind != GoalKind.DEBT_PAYOFF || baseline != null);
    }

    public Money reservedOn(UUID accountId, LocalDate asOf) {
        return archived ? Money.zero(CurrencyCode.BRL) : netOn(accountId, asOf);
    }

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

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new GoalNotFoundException(id);
        }
    }

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

    public LocalDate deadline() {
        return deadline;
    }

    public GoalPriority priority() {
        return priority;
    }

    public String description() {
        return description;
    }

    public boolean isArchived() {
        return archived;
    }

    public List<GoalReservation> reservations() {
        return List.copyOf(reservations);
    }

    private static String requireName(String value) {
        if (value == null || value.isBlank()) {
            throw new GoalNameRequiredException();
        }
        String stripped = value.strip();
        int length = stripped.length();
        PlainText.requireClean(stripped, "name", false);
        if (length > NAME_MAX_LENGTH) {
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

    private static Money requireMetricTarget(Money value) {
        if (value == null) {
            throw new GoalFieldRequiredException("target");
        }
        if (value.isNegative()) {
            throw new GoalMetricTargetNegativeException();
        }
        if (!CurrencyCode.BRL.equals(value.currency())) {
            throw new com.moneylog.core.goal.exception.GoalExceptions.GoalReservationAccountNotEligibleException("só BRL no MVP.");
        }
        return AmountLimit.ensureWithinLimit(value, "target");
    }

    private static String requirePlanNote(String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        String stripped = value.strip();
        PlainText.requireClean(stripped, "planNote", true);
        if (stripped.length() > PLAN_NOTE_MAX_LENGTH) {
            throw new GoalTextTooLongException("planNote", PLAN_NOTE_MAX_LENGTH);
        }
        return stripped;
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
