package com.moneylog.core.commitment.app;

import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.sk.persistence.Versioned;

/** O compromisso com a versão e os carimbos (a API mostra os derivados do agregado). */
public record CommitmentView(Versioned<TrackedCommitment> stored) {

    public TrackedCommitment commitment() {
        return stored.value();
    }
}
