package com.moneylog.core.asset.app;

import java.time.LocalDate;

import com.moneylog.core.asset.domain.AssetAvailability;
import com.moneylog.core.asset.domain.AssetKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;

public record AssetSpec(AssetKind kind, String name, String employer, Money balance, LocalDate balanceDate, Money monthlyDeposit,
                        AssetAvailability availability, boolean countsAsReserve, Provenance provenance) {
}
