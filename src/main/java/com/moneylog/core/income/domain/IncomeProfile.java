package com.moneylog.core.income.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileArchivedException;
import com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileInvalidException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.Competence;

public final class IncomeProfile {

    public static final int SOURCE_MAX_LENGTH = 100;
    public static final int MAX_DAY = 28;
    public static final int MAX_DEPENDENTS = 20;
    public static final int MAX_DEDUCTIONS = 30;

    private final UUID id;
    private final UUID userId;
    private String source;
    private Money gross;
    private int dependents;
    private PayrollAdvance advance;
    private int balanceDayOfMonth;
    private List<PayrollDeduction> deductions;
    private ThirteenthSettings thirteenth;
    private boolean archived;
    private Provenance provenance = Provenance.DEFAULT;

    private IncomeProfile(UUID id, UUID userId, String source, Money gross, int dependents, PayrollAdvance advance,
                          int balanceDayOfMonth, List<PayrollDeduction> deductions, ThirteenthSettings thirteenth, boolean archived) {
        if (id == null || userId == null) {
            throw new IncomeProfileInvalidException("id e dono são obrigatórios.");
        }
        this.id = id;
        this.userId = userId;
        this.archived = archived;
        fill(source, gross, dependents, advance, balanceDayOfMonth, deductions, thirteenth);
    }

    public static IncomeProfile create(UUID id, UUID userId, String source, Money gross, int dependents, PayrollAdvance advance,
                                       int balanceDayOfMonth, List<PayrollDeduction> deductions, ThirteenthSettings thirteenth) {
        return new IncomeProfile(id, userId, source, gross, dependents, advance, balanceDayOfMonth, deductions, thirteenth, false);
    }

    public static IncomeProfile restore(UUID id, UUID userId, String source, Money gross, int dependents, PayrollAdvance advance,
                                        int balanceDayOfMonth, List<PayrollDeduction> deductions, ThirteenthSettings thirteenth,
                                        boolean archived) {
        return new IncomeProfile(id, userId, source, gross, dependents, advance, balanceDayOfMonth, deductions, thirteenth, archived);
    }

    public void revise(String newSource, Money newGross, int newDependents, PayrollAdvance newAdvance, int newBalanceDayOfMonth,
                       List<PayrollDeduction> newDeductions, ThirteenthSettings newThirteenth) {
        ensureActive();
        fill(newSource, newGross, newDependents, newAdvance, newBalanceDayOfMonth, newDeductions, newThirteenth);
    }

    public void defineProvenance(Provenance newProvenance) {
        this.provenance = Provenance.orDefault(newProvenance);
    }

    public void archive() {
        ensureActive();
        archived = true;
    }

    public void ensureActive() {
        if (archived) {
            throw new IncomeProfileArchivedException();
        }
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new com.moneylog.core.income.exception.IncomeExceptions.IncomeProfileNotFoundException(id);
        }
    }

    private void fill(String newSource, Money newGross, int newDependents, PayrollAdvance newAdvance, int newBalanceDayOfMonth,
                      List<PayrollDeduction> newDeductions, ThirteenthSettings newThirteenth) {
        String validSource = newSource == null ? "" : newSource.strip();
        if (validSource.isEmpty() || validSource.length() > SOURCE_MAX_LENGTH) {
            throw new IncomeProfileInvalidException("a fonte pagadora tem de 1 a " + SOURCE_MAX_LENGTH + " caracteres.");
        }
        if (newGross == null || !newGross.isPositive()) {
            throw new IncomeProfileInvalidException("o bruto precisa ser maior que zero.");
        }
        AmountLimit.ensureWithinLimit(newGross, "gross");
        if (newDependents < 0 || newDependents > MAX_DEPENDENTS) {
            throw new IncomeProfileInvalidException("os dependentes vão de 0 a " + MAX_DEPENDENTS + ".");
        }
        if (newBalanceDayOfMonth < 1 || newBalanceDayOfMonth > MAX_DAY) {
            throw new IncomeProfileInvalidException("o dia do saldo vai de 1 a " + MAX_DAY + ".");
        }
        List<PayrollDeduction> validDeductions = newDeductions == null ? List.of() : List.copyOf(newDeductions);
        if (validDeductions.size() > MAX_DEDUCTIONS) {
            throw new IncomeProfileInvalidException("no máximo " + MAX_DEDUCTIONS + " rubricas.");
        }
        this.source = validSource;
        this.gross = newGross;
        this.dependents = newDependents;
        this.advance = newAdvance;
        this.balanceDayOfMonth = newBalanceDayOfMonth;
        this.deductions = validDeductions;
        this.thirteenth = newThirteenth == null ? ThirteenthSettings.disabled() : newThirteenth;
    }

    public LocalDate advanceDate(Competence competence) {
        return advance == null ? null : competence.value().atDay(advance.dayOfMonth());
    }

    public LocalDate balanceDate(Competence competence) {
        return competence.plusMonths(1).value().atDay(balanceDayOfMonth);
    }

    public LocalDate thirteenthFirstDate(Competence competence) {
        return thirteenth.enabled() && competence.value().getMonthValue() == thirteenth.firstInstallmentMonth()
                ? competence.value().atDay(balanceDayOfMonth) : null;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public String source() {
        return source;
    }

    public Money gross() {
        return gross;
    }

    public int dependents() {
        return dependents;
    }

    public PayrollAdvance advance() {
        return advance;
    }

    public int balanceDayOfMonth() {
        return balanceDayOfMonth;
    }

    public List<PayrollDeduction> deductions() {
        return deductions;
    }

    public ThirteenthSettings thirteenth() {
        return thirteenth;
    }

    public boolean isArchived() {
        return archived;
    }

    public Provenance provenance() {
        return provenance;
    }
}
