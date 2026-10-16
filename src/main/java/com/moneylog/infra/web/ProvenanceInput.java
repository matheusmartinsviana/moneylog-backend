package com.moneylog.infra.web;

import com.moneylog.core.sk.provenance.Provenance;

public record ProvenanceInput(String origin, String confidence) {

    public static Provenance toDomain(ProvenanceInput input) {
        return input == null ? null : Provenance.parse(input.origin(), input.confidence());
    }
}
