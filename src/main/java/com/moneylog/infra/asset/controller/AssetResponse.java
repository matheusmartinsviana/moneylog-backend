package com.moneylog.infra.asset.controller;

import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.asset.domain.Asset;
import com.moneylog.core.asset.domain.AssetAvailability;
import com.moneylog.core.asset.domain.AssetKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.provenance.Provenance;

public record AssetResponse(UUID id, AssetKind kind, String name, String employer, Money balance, LocalDate balanceDate,
                            Money monthlyDeposit, AssetAvailability availability, boolean countsAsReserve, Status status,
                            Provenance provenance, List<BalancePointResponse> history, long version, Instant createdAt, Instant updatedAt) {

    public enum Status {
        ACTIVE,
        ARCHIVED
    }

    public record BalancePointResponse(Money balance, LocalDate date, Instant recordedAt) {
    }

    public static AssetResponse of(Versioned<Asset> stored) {
        Asset a = stored.value();
        return new AssetResponse(a.id(), a.kind(), a.name(), a.employer(), a.balance(), a.balanceDate(), a.monthlyDeposit(),
                a.availability(), a.countsAsReserve(), a.isArchived() ? Status.ARCHIVED : Status.ACTIVE,
                a.provenance(),
                a.history().stream().map(p -> new BalancePointResponse(p.balance(), p.date(), p.recordedAt())).toList(), stored.version(),
                stored.createdAt(), stored.updatedAt());
    }
}
