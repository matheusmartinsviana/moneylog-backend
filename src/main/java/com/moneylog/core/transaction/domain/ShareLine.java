package com.moneylog.core.transaction.domain;

import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.provenance.Provenance;

public record ShareLine(UUID personId, Integer bps, Money amount, Provenance provenance) {

    public ShareLine {
        provenance = Provenance.orDefault(provenance);
    }

    public ShareLine(UUID personId, Integer bps, Money amount) {
        this(personId, bps, amount, null);
    }

    public boolean isOwner() {
        return personId == null;
    }
}
