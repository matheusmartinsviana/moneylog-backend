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
    /** Total de dívidas fixado em "cadastrei tudo" ({@code DEBT_PAYOFF}) ou valor da medida na criação/re-ancoragem ({@code METRIC}); {@code null} enquanto "montando". */
    private Money baseline;
    private Instant baselineAt;
    /** Só {@code METRIC} (ADR-016). */
    private final GoalMetric metric;
    private GoalDirection direction;
    private final List<GoalReservation> reservations = new ArrayList<>();
    /** Plano declarado (ADR-016): texto curto e ações. */
    private String planNote;
    private final List<GoalAction> actions = new ArrayList<>();
    /** Destaque da Home (ADR-018): só {@code SAVINGS} ativa; no máximo uma por usuário (garantido pelo caso de uso e pelo índice único parcial). */
    private boolean featured;
    /** Metadados das fotos (ADR-018), na ordem de exibição: a primeira é a capa. Só {@code SAVINGS}. */
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
            // nome opcional: sem nome, o front mostra o rótulo gerado pela medida
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

    /**
     * Meta "quitar todas as dívidas" (ADR-013): sem alvo e sem reservas; fica "montando" (sem baseline) até {@link #anchorBaseline}. O
     * progresso e o restante vêm do total de dívidas, que o chamador informa.
     */
    public static Goal createDebtPayoff(UUID id, UUID userId, String name, LocalDate deadline, GoalPriority priority, String description) {
        return new Goal(id, userId, name, null, deadline, priority, description, false, GoalKind.DEBT_PAYOFF, null, null, null, null);
    }

    /**
     * Meta de medida (ADR-016): acompanha {@code metric} até {@code target}, a partir de {@code baseline} (o valor da medida agora,
     * capturado pelo chamador na criação, sem passo "montando"). O sentido é derivado: {@code target < baseline} diminui,
     * {@code target > baseline} aumenta; igual é recusado. Sem nome, o front mostra o rótulo gerado pela medida. Sem reservas.
     */
    @SuppressWarnings("java:S107")
    public static Goal createMetric(UUID id, UUID userId, String name, GoalMetric metric, Money target, Money baseline, Instant now,
                                    LocalDate deadline, GoalPriority priority, String description) {
        return new Goal(id, userId, name, target, deadline, priority, description, false, GoalKind.METRIC, baseline,
                Objects.requireNonNull(now, "now"), metric, null);
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
        return restore(id, userId, name, target, deadline, priority, description, archived, kind, baseline, baselineAt, null, null, stored);
    }

    /** Reidratação completa (inclui a medida e o sentido da meta {@code METRIC}). */
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

    // ------------------------------------------------------------------ comandos

    /** Edita os dados da meta (não as reservas). Meta arquivada não é editada. */
    public void update(String newName, Money newTarget, LocalDate newDeadline, GoalPriority newPriority, String newDescription) {
        ensureNotArchived();
        Goal valid = new Goal(id, userId, newName, kind == GoalKind.DEBT_PAYOFF ? baseline : newTarget, newDeadline, newPriority,
                newDescription, false, kind, baseline, baselineAt, metric, null);
        this.name = valid.name;
        this.direction = valid.direction; // METRIC: o sentido acompanha o novo alvo (igual à baseline é recusado acima)
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
        this.featured = false; // meta arquivada não fica em destaque (ADR-018)
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
     * Fixa (ou re-ancora, P8) a baseline com o valor atual. {@code DEBT_PAYOFF}: total zero é recusado (não há o que quitar; a meta
     * nunca vira "conquistada" sem ter tido dívida) e o alvo passa a ser a baseline. {@code METRIC} (ADR-016): vale qualquer valor
     * (inclusive zero ou negativo); o sentido é re-derivado e alvo igual ao valor atual é recusado, sem alterar a meta.
     */
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

    // ------------------------------------------------------------------ medida (ADR-016)

    public boolean isMetric() {
        return kind == GoalKind.METRIC;
    }

    /** {@code null} fora de {@code METRIC}. */
    public GoalMetric metric() {
        return metric;
    }

    /** {@code null} fora de {@code METRIC}. */
    public GoalDirection direction() {
        return direction;
    }

    /**
     * Progresso a partir do valor ATUAL da medida (calculado pelo chamador na fonte de cada medida). {@code DECREASE}:
     * {@code (baseline − current) / (baseline − target)}; {@code INCREASE}: {@code (current − baseline) / (target − baseline)}; limitado a
     * 0..1. {@code reached} é a comparação exata com o alvo; a razão só chega a 1,0000 quando {@code reached} (arredondar para cima não
     * promete conquista que ainda não houve). Monotônica em {@code current} na direção da meta.
     */
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

    /** Estimativa por mês da distância restante até o prazo (como S-156); atingida → 0,00; sem prazo ou prazo vencido → {@code null}. */
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

    // ------------------------------------------------------------------ destaque e fotos (ADR-018)

    public boolean isFeatured() {
        return featured;
    }

    /** Fotos na ordem de exibição (a primeira é a capa). */
    public List<GoalPhoto> photos() {
        return List.copyOf(photos);
    }

    /** A capa é a primeira foto; {@code null} sem fotos. */
    public UUID coverPhotoId() {
        return photos.isEmpty() ? null : photos.getFirst().id();
    }

    /** Reidratação de destaque e fotos (usado só pelo mapper). */
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

    /** Põe a meta em destaque. O caso de uso desmarca a anterior antes. */
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

    /** Acrescenta a foto ao fim da lista. Meta arquivada, de outro tipo ou cheia recusa. */
    public GoalPhoto addPhoto(GoalPhoto photo, int maxPerGoal) {
        ensureCanAddPhoto(maxPerGoal);
        photos.add(photo);
        return photo;
    }

    /** Confere tipo, arquivamento e limite por meta sem alterar nada (permite recusar antes de decodificar a imagem). */
    public void ensureCanAddPhoto(int maxPerGoal) {
        ensurePhotosAllowed();
        if (photos.size() >= maxPerGoal) {
            throw new GoalPhotoLimitReachedException(maxPerGoal);
        }
    }

    /** Remove a foto; se era a capa, a próxima assume. */
    public GoalPhoto removePhoto(UUID photoId) {
        ensurePhotosAllowed();
        return photos.remove(indexOfPhoto(photoId));
    }

    /** Leva a foto para a posição 0. */
    public void setCover(UUID photoId) {
        ensurePhotosAllowed();
        photos.addFirst(photos.remove(indexOfPhoto(photoId)));
    }

    /** Nova ordem: precisa ser exatamente o conjunto de fotos da meta, sem repetir. */
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

    // ------------------------------------------------------------------ plano declarado (ADR-016)

    public String planNote() {
        return planNote;
    }

    public List<GoalAction> actions() {
        return List.copyOf(actions);
    }

    /** O dono declarou um plano: texto ou ao menos uma ação. */
    public boolean hasDeclaredPlan() {
        return planNote != null || !actions.isEmpty();
    }

    /** Reidratação do plano declarado (usado só pelo mapper). */
    public Goal restorePlan(String storedNote, List<GoalAction> storedActions) {
        this.planNote = requirePlanNote(storedNote);
        this.actions.clear();
        this.actions.addAll(storedActions);
        return this;
    }

    /** Define (ou limpa, com texto vazio/{@code null}) o texto do plano. */
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

    /** Marca (ou desmarca) o período da ação; idempotente. */
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

    /**
     * Trajetória para o plano automático e a consistência; {@code null} se a meta não tem trajetória (quitar dívidas ainda "montando").
     *
     * @param createdOn início de {@code SAVINGS} (data da criação da meta)
     */
    public GoalPlanner.Track track(LocalDate createdOn, ZoneId zone) {
        return switch (kind) {
            case SAVINGS -> new GoalPlanner.Track(false, Money.zero(CurrencyCode.BRL), createdOn, target, deadline);
            case METRIC -> new GoalPlanner.Track(direction == GoalDirection.DECREASE, baseline, baselineAt.atZone(zone).toLocalDate(), target,
                    deadline);
            case DEBT_PAYOFF -> baseline == null ? null
                    : new GoalPlanner.Track(true, baseline, baselineAt.atZone(zone).toLocalDate(), Money.zero(CurrencyCode.BRL), deadline);
        };
    }

    /** Existe plano automático válido: prazo + alvo (quitar dívidas exige a baseline). Não depende do valor atual. */
    public boolean hasAutoPlan() {
        return deadline != null && (kind != GoalKind.DEBT_PAYOFF || baseline != null);
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
