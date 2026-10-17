package com.moneylog.core.asset.domain;

import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.asset.exception.AssetExceptions.AssetArchivedException;
import com.moneylog.core.asset.exception.AssetExceptions.AssetInvalidException;
import com.moneylog.core.asset.exception.AssetExceptions.AssetNotFoundException;
import com.moneylog.core.sk.money.AmountLimit;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.sk.time.FinancialDates;

public final class Asset {

    public static final int NAME_MAX_LENGTH = 100;
    public static final int EMPLOYER_MAX_LENGTH = 100;

    private final UUID id;
    private final UUID userId;
    private AssetKind kind;
    private String name;
    private String employer;
    private Money balance;
    private LocalDate balanceDate;
    private Money monthlyDeposit;
    private AssetAvailability availability;
    private boolean countsAsReserve;
    private boolean archived;
    private Provenance provenance = Provenance.DEFAULT;
    private final List<BalancePoint> history;

    private Asset(UUID id, UUID userId, AssetKind kind, String name, String employer, Money balance, LocalDate balanceDate,
                  Money monthlyDeposit, AssetAvailability availability, boolean countsAsReserve, boolean archived,
                  List<BalancePoint> history) {
        if (id == null || userId == null) {
            throw new AssetInvalidException("id e dono são obrigatórios.");
        }
        this.id = id;
        this.userId = userId;
        this.archived = archived;
        this.history = new ArrayList<>(history);
        fillDetails(kind, name, employer, monthlyDeposit, availability, countsAsReserve);
        this.balance = validBalance(balance);
        this.balanceDate = FinancialDates.requireInWindow(requireDate(balanceDate), "balanceDate");
    }

    public static Asset create(UUID id, UUID userId, AssetKind kind, String name, String employer, Money balance, LocalDate balanceDate,
                               Money monthlyDeposit, AssetAvailability availability, boolean countsAsReserve, Instant now) {
        Asset asset = new Asset(id, userId, kind, name, employer, balance, balanceDate, monthlyDeposit, availability, countsAsReserve,
                false, List.of());
        asset.history.add(new BalancePoint(asset.balance, asset.balanceDate, now));
        return asset;
    }

    public static Asset restore(UUID id, UUID userId, AssetKind kind, String name, String employer, Money balance, LocalDate balanceDate,
                                Money monthlyDeposit, AssetAvailability availability, boolean countsAsReserve, boolean archived,
                                List<BalancePoint> history) {
        return new Asset(id, userId, kind, name, employer, balance, balanceDate, monthlyDeposit, availability, countsAsReserve, archived,
                history == null ? List.of() : history);
    }

    public void revise(AssetKind newKind, String newName, String newEmployer, Money newMonthlyDeposit, AssetAvailability newAvailability,
                       boolean newCountsAsReserve) {
        ensureActive();
        fillDetails(newKind, newName, newEmployer, newMonthlyDeposit, newAvailability, newCountsAsReserve);
    }

    public void defineProvenance(Provenance newProvenance) {
        this.provenance = Provenance.orDefault(newProvenance);
    }

    public BalancePoint recordBalance(Money newBalance, LocalDate date, Instant now) {
        ensureActive();
        Money valid = validBalance(newBalance);
        FinancialDates.requireInWindow(requireDate(date), "date");
        BalancePoint point = new BalancePoint(valid, date, now);
        history.add(0, point);
        if (!date.isBefore(balanceDate)) {
            this.balance = valid;
            this.balanceDate = date;
        }
        return point;
    }

    public void archive() {
        ensureActive();
        archived = true;
    }

    public void ensureActive() {
        if (archived) {
            throw new AssetArchivedException();
        }
    }

    public void ensureBelongsTo(UUID requesterId) {
        if (!userId.equals(requesterId)) {
            throw new AssetNotFoundException(id);
        }
    }

    public boolean isReserved() {
        return availability == AssetAvailability.RESERVED;
    }

    public boolean isEmergencyReserve() {
        return !archived && availability == AssetAvailability.AVAILABLE && countsAsReserve && kind != AssetKind.FGTS;
    }

    private void fillDetails(AssetKind newKind, String newName, String newEmployer, Money newMonthlyDeposit,
                             AssetAvailability newAvailability, boolean newCountsAsReserve) {
        if (newKind == null || newAvailability == null) {
            throw new AssetInvalidException("tipo e disponibilidade são obrigatórios.");
        }
        String validName = newName == null ? "" : newName.strip();
        if (validName.isEmpty() || validName.length() > NAME_MAX_LENGTH) {
            throw new AssetInvalidException("o nome tem de 1 a " + NAME_MAX_LENGTH + " caracteres.");
        }
        String validEmployer = newEmployer == null || newEmployer.isBlank() ? null : newEmployer.strip();
        if (validEmployer != null && validEmployer.length() > EMPLOYER_MAX_LENGTH) {
            throw new AssetInvalidException("o empregador tem até " + EMPLOYER_MAX_LENGTH + " caracteres.");
        }
        if (newKind == AssetKind.FGTS && newAvailability != AssetAvailability.RESERVED) {
            throw new AssetInvalidException("o FGTS é sempre não disponível (RESERVED).");
        }
        if (newCountsAsReserve && newAvailability != AssetAvailability.AVAILABLE) {
            throw new AssetInvalidException("só um ativo disponível conta na reserva de emergência.");
        }
        if (newMonthlyDeposit != null) {
            if (newMonthlyDeposit.isNegative() || !CurrencyCode.BRL.equals(newMonthlyDeposit.currency())) {
                throw new AssetInvalidException("o depósito mensal não pode ser negativo e é em BRL.");
            }
            AmountLimit.ensureWithinLimit(newMonthlyDeposit, "monthlyDeposit");
        }
        this.kind = newKind;
        this.name = validName;
        this.employer = validEmployer;
        this.monthlyDeposit = newMonthlyDeposit;
        this.availability = newAvailability;
        this.countsAsReserve = newCountsAsReserve;
    }

    private static Money validBalance(Money value) {
        if (value == null || value.isNegative() || !CurrencyCode.BRL.equals(value.currency())) {
            throw new AssetInvalidException("o saldo é obrigatório, em BRL e não negativo.");
        }
        return AmountLimit.ensureWithinLimit(value, "balance");
    }

    private static LocalDate requireDate(LocalDate date) {
        if (date == null) {
            throw new AssetInvalidException("a data do saldo é obrigatória.");
        }
        return date;
    }

    public UUID id() {
        return id;
    }

    public UUID userId() {
        return userId;
    }

    public AssetKind kind() {
        return kind;
    }

    public String name() {
        return name;
    }

    public String employer() {
        return employer;
    }

    public Money balance() {
        return balance;
    }

    public LocalDate balanceDate() {
        return balanceDate;
    }

    public Money monthlyDeposit() {
        return monthlyDeposit;
    }

    public AssetAvailability availability() {
        return availability;
    }

    public boolean countsAsReserve() {
        return countsAsReserve;
    }

    public boolean isArchived() {
        return archived;
    }

    public Provenance provenance() {
        return provenance;
    }

    public List<BalancePoint> history() {
        return history.stream().sorted(Comparator.comparing(BalancePoint::recordedAt).reversed()).toList();
    }
}
