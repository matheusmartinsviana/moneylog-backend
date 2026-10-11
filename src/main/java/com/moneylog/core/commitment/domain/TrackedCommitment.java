package com.moneylog.core.commitment.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Objects;
import java.util.Optional;
import java.util.TreeMap;
import java.util.UUID;

import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.ScheduleItemAlreadyPaidException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.ScheduleItemNotFoundException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.time.FinancialDates;

public final class TrackedCommitment {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int NOTES_MAX_LENGTH = 1000;
    public static final int MAX_ITEMS = 120;

    private final UUID id;
    private final UUID userId;
    private final CommitmentKind kind;
    private String name;
    private String counterparty;
    private String notes;
    private final Money total;
    private Money informedPaid;
    private Integer installmentsTotal;
    private Integer installmentsPaid;
    private LocalDate nextDueDate;
    private final List<ScheduleItem> schedule;

    private TrackedCommitment(UUID id, UUID userId, CommitmentKind kind, String name, String counterparty, String notes, Money total,
                              Money informedPaid, Integer installmentsTotal, Integer installmentsPaid, LocalDate nextDueDate,
                              List<ScheduleItem> schedule) {
        this.id = Objects.requireNonNull(id, "id");
        this.userId = Objects.requireNonNull(userId, "userId");
        this.kind = Objects.requireNonNull(kind, "kind");
        if (name == null || name.isBlank() || name.strip().length() > NAME_MAX_LENGTH) {
            throw new InvalidCommitmentException("o nome é obrigatório e tem até " + NAME_MAX_LENGTH + " caracteres.");
        }
        this.name = name.strip();
        if (counterparty != null && counterparty.strip().length() > NAME_MAX_LENGTH) {
            throw new InvalidCommitmentException("a contraparte tem até " + NAME_MAX_LENGTH + " caracteres.");
        }
        this.counterparty = counterparty == null || counterparty.isBlank() ? null : counterparty.strip();
        if (notes != null && notes.length() > NOTES_MAX_LENGTH) {
            throw new InvalidCommitmentException("as observações têm até " + NOTES_MAX_LENGTH + " caracteres.");
        }
        this.notes = notes;
        FinancialDates.requireInWindow(nextDueDate, "nextDueDate");
        this.nextDueDate = nextDueDate;
        this.schedule = new ArrayList<>(schedule);
        if (total != null) {
            requireNonNegative(total, "total");
            if (!total.isPositive()) {
                throw new InvalidCommitmentException("o total, quando informado, precisa ser maior que zero.");
            }
        }
        this.total = total;
        if (informedPaid != null) {
            requireNonNegative(informedPaid, "paidAmount");
        }
        this.informedPaid = informedPaid;
        this.installmentsTotal = installmentsTotal;
        this.installmentsPaid = installmentsPaid;
        validateShape();
    }

    private static void requireNonNegative(Money value, String field) {
        if (value.isNegative() || !CurrencyCode.BRL.equals(value.currency())) {
            throw new InvalidCommitmentException(field + " não pode ser negativo e é em BRL.");
        }
        AmountLimit.ensureWithinLimit(value, field);
    }

    private void validateShape() {
        if (kind == CommitmentKind.LONG_TERM_PROJECT) {
            if (schedule.isEmpty() || schedule.size() > MAX_ITEMS) {
                throw new InvalidCommitmentException("o projeto de longo prazo exige cronograma (1 a " + MAX_ITEMS + " itens).");
            }
            if (informedPaid != null || installmentsTotal != null || installmentsPaid != null) {
                throw new InvalidCommitmentException("no projeto de longo prazo o pago vem do cronograma (itens pagos).");
            }
            Money sum = Money.sum(schedule.stream().map(ScheduleItem::amount).toList(), CurrencyCode.BRL);
            if (total != null && !sum.equals(total)) {
                throw new InvalidCommitmentException("a soma do cronograma (" + sum.toPlainString() + ") não fecha com o total ("
                        + total.toPlainString() + ").");
            }
            for (int i = 0; i < schedule.size(); i++) {
                if (schedule.get(i).position() != i + 1) {
                    throw new InvalidCommitmentException("as posições do cronograma são 1..n, em sequência.");
                }
            }
        } else {
            if (!schedule.isEmpty() || total != null) {
                throw new InvalidCommitmentException("o compromisso de terceiro não tem cronograma nem total: só o que foi informado.");
            }
            if (installmentsTotal == null || installmentsPaid == null || installmentsTotal < 1 || installmentsPaid < 0
                    || installmentsPaid > installmentsTotal) {
                throw new InvalidCommitmentException("informe parcelas pagas (0..total) e total de parcelas (≥ 1).");
            }
        }
    }

    public static TrackedCommitment longTermProject(UUID id, UUID userId, String name, String counterparty, String notes, Money total,
                                                    List<ScheduleItem> schedule) {
        return new TrackedCommitment(id, userId, CommitmentKind.LONG_TERM_PROJECT, name, counterparty, notes, total, null, null, null,
                null, schedule);
    }

    public static TrackedCommitment trackedThirdParty(UUID id, UUID userId, String name, String counterparty, String notes,
                                                      int installmentsTotal, int installmentsPaid, Money paidAmount,
                                                      LocalDate nextDueDate) {
        return new TrackedCommitment(id, userId, CommitmentKind.TRACKED_THIRD_PARTY, name, counterparty, notes, null, paidAmount,
                installmentsTotal, installmentsPaid, nextDueDate, List.of());
    }

    public static TrackedCommitment restore(UUID id, UUID userId, CommitmentKind kind, String name, String counterparty, String notes,
                                            Money total, Money informedPaid, Integer installmentsTotal, Integer installmentsPaid,
                                            LocalDate nextDueDate, List<ScheduleItem> schedule) {
        return new TrackedCommitment(id, userId, kind, name, counterparty, notes, total, informedPaid, installmentsTotal,
                installmentsPaid, nextDueDate, schedule);
    }

    public void markItemPaid(int position, LocalDate paidOn) {
        FinancialDates.requireInWindow(paidOn, "paidOn");
        if (kind != CommitmentKind.LONG_TERM_PROJECT) {
            throw new InvalidCommitmentException("só o projeto de longo prazo tem cronograma.");
        }
        ScheduleItem item = schedule.stream().filter(i -> i.position() == position).findFirst()
                .orElseThrow(() -> new ScheduleItemNotFoundException(id, position));
        if (item.isPaid()) {
            throw new ScheduleItemAlreadyPaidException(position);
        }
        schedule.set(position - 1, item.markPaid(paidOn));
    }

    public void updateProgress(int newInstallmentsPaid, Money newPaidAmount, LocalDate newNextDueDate, String newNotes) {
        updateProgress(newInstallmentsPaid, newPaidAmount, newNextDueDate, newNotes, null, null);
    }

    public void updateProgress(int newInstallmentsPaid, Money newPaidAmount, LocalDate newNextDueDate, String newNotes, String newName,
                               String newCounterparty) {
        if (newName != null && (newName.isBlank() || newName.strip().length() > NAME_MAX_LENGTH)
                || newCounterparty != null && newCounterparty.strip().length() > NAME_MAX_LENGTH) {
            throw new InvalidCommitmentException("nome e contraparte têm até " + NAME_MAX_LENGTH + " caracteres.");
        }
        if (kind != CommitmentKind.TRACKED_THIRD_PARTY) {
            throw new InvalidCommitmentException("só o compromisso de terceiro tem o progresso informado.");
        }
        if (newInstallmentsPaid < 0 || newInstallmentsPaid > installmentsTotal) {
            throw new InvalidCommitmentException("parcelas pagas fora de 0.." + installmentsTotal + ".");
        }
        if (newPaidAmount != null) {
            requireNonNegative(newPaidAmount, "paidAmount");
        }
        FinancialDates.requireInWindow(newNextDueDate, "nextDueDate");
        if (newNotes != null && newNotes.length() > NOTES_MAX_LENGTH) {
            throw new InvalidCommitmentException("as observações têm até " + NOTES_MAX_LENGTH + " caracteres.");
        }
        if (newName != null) {
            this.name = newName.strip();
        }
        if (newCounterparty != null) {
            this.counterparty = newCounterparty.isBlank() ? null : newCounterparty.strip();
        }
        this.installmentsPaid = newInstallmentsPaid;
        this.informedPaid = newPaidAmount;
        this.nextDueDate = newNextDueDate;
        this.notes = newNotes;
    }

    public Money paid() {
        if (kind == CommitmentKind.LONG_TERM_PROJECT) {
            return Money.sum(schedule.stream().filter(ScheduleItem::isPaid).map(ScheduleItem::amount).toList(), CurrencyCode.BRL);
        }
        return informedPaid;
    }

    public Money nominalTotal() {
        if (kind != CommitmentKind.LONG_TERM_PROJECT) {
            return null;
        }
        return Money.sum(schedule.stream().map(ScheduleItem::amount).toList(), CurrencyCode.BRL);
    }

    public Money nominalBalance() {
        Money t = nominalTotal();
        return t == null ? null : t.subtract(paid());
    }

    public BigDecimal progress() {
        Money t = nominalTotal();
        if (t == null) {
            return null;
        }
        return BigDecimal.valueOf(paid().cents()).divide(BigDecimal.valueOf(t.cents()), 4, RoundingMode.HALF_EVEN);
    }

    public Money directPending() {
        return Money.sum(schedule.stream().filter(i -> !i.isPaid() && i.type() != ScheduleItemType.FINANCING).map(ScheduleItem::amount)
                .toList(), CurrencyCode.BRL);
    }

    public Money financingPending() {
        return Money.sum(schedule.stream().filter(i -> !i.isPaid() && i.type() == ScheduleItemType.FINANCING).map(ScheduleItem::amount)
                .toList(), CurrencyCode.BRL);
    }

    public Optional<ScheduleItem> nextItem() {
        return schedule.stream().filter(i -> !i.isPaid() && i.dueDate() != null && i.type() != ScheduleItemType.FINANCING)
                .min(java.util.Comparator.comparing(ScheduleItem::dueDate).thenComparing(ScheduleItem::position));
    }

    public LocalDate nextDueDate() {
        return kind == CommitmentKind.LONG_TERM_PROJECT ? nextItem().map(ScheduleItem::dueDate).orElse(null) : nextDueDate;
    }

    public Map<YearMonth, Money> pendingByMonth() {
        Map<YearMonth, Money> result = new TreeMap<>();
        for (ScheduleItem i : schedule) {
            if (!i.isPaid() && i.dueDate() != null && i.type() != ScheduleItemType.FINANCING) {
                result.merge(YearMonth.from(i.dueDate()), i.amount(), Money::add);
            }
        }
        return result;
    }

    public Integer remainingInstallments() {
        return kind == CommitmentKind.TRACKED_THIRD_PARTY ? installmentsTotal - installmentsPaid : null;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public CommitmentKind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String counterparty() {
        return counterparty;
    }

    public String notes() {
        return notes;
    }

    public Money informedPaid() {
        return informedPaid;
    }

    public Money declaredTotal() {
        return total;
    }

    public Integer installmentsTotal() {
        return installmentsTotal;
    }

    public Integer installmentsPaid() {
        return installmentsPaid;
    }

    public LocalDate storedNextDueDate() {
        return nextDueDate;
    }

    public List<ScheduleItem> schedule() {
        return List.copyOf(schedule);
    }
}
