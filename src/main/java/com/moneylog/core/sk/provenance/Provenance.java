package com.moneylog.core.sk.provenance;

import java.util.Locale;

public record Provenance(Origin origin, Confidence confidence) {

    public static final Provenance DEFAULT = new Provenance(Origin.INFORMED, Confidence.CONFIRMED);

    public enum Origin {
        PRINT,
        PAYSLIP,
        CONTRACT,
        INFORMED
    }

    public enum Confidence {
        CONFIRMED,
        ESTIMATED
    }

    public Provenance {
        origin = origin == null ? Origin.INFORMED : origin;
        confidence = confidence == null ? Confidence.CONFIRMED : confidence;
    }

    public static Provenance parse(String origin, String confidence) {
        return new Provenance(parseEnum(Origin.class, origin), parseEnum(Confidence.class, confidence));
    }

    public static Provenance orDefault(Provenance value) {
        return value == null ? DEFAULT : value;
    }

    public static Provenance orCurrent(Provenance value, Provenance current) {
        return value == null ? orDefault(current) : value;
    }

    public boolean estimated() {
        return confidence == Confidence.ESTIMATED;
    }

    private static <E extends Enum<E>> E parseEnum(Class<E> type, String value) {
        if (value == null) {
            return null;
        }
        try {
            return Enum.valueOf(type, value.strip().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new ProvenanceInvalidException(type.getSimpleName().toLowerCase(Locale.ROOT), value);
        }
    }
}
