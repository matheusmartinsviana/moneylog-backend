package com.moneylog.infra.persistence;

import com.moneylog.core.sk.provenance.Provenance;

import jakarta.persistence.Column;
import jakarta.persistence.Embeddable;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;

@Embeddable
public class ProvenanceColumns {

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance_origin", nullable = false, length = 10)
    private Provenance.Origin origin = Provenance.Origin.INFORMED;

    @Enumerated(EnumType.STRING)
    @Column(name = "provenance_confidence", nullable = false, length = 9)
    private Provenance.Confidence confidence = Provenance.Confidence.CONFIRMED;

    protected ProvenanceColumns() {
    }

    public static ProvenanceColumns of(Provenance provenance) {
        Provenance valid = Provenance.orDefault(provenance);
        ProvenanceColumns columns = new ProvenanceColumns();
        columns.origin = valid.origin();
        columns.confidence = valid.confidence();
        return columns;
    }

    public Provenance toDomain() {
        return new Provenance(origin, confidence);
    }
}
