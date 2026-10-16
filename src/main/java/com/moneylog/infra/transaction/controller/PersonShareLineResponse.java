package com.moneylog.infra.transaction.controller;

import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;
import com.moneylog.core.transaction.domain.ShareLine;

public record PersonShareLineResponse(UUID personId, Integer bps, Money amount, Provenance provenance) {

    public static PersonShareLineResponse of(ShareLine line) {
        return new PersonShareLineResponse(line.personId(), line.bps(), line.amount(), line.provenance());
    }
}
