package com.moneylog.core.insights.domain;

import java.util.List;
import java.util.Map;
import java.util.Objects;

public record Insight(String type, String key, Map<String, String> params, List<String> factRefs, Severity severity, int priority,
                      Period period, Basis basis, Confidence confidence, String subjectId, long magnitudeCents) {

    public Insight {
        Objects.requireNonNull(type, "type");
        Objects.requireNonNull(key, "key");
        Objects.requireNonNull(severity, "severity");
        params = Map.copyOf(params);
        factRefs = List.copyOf(factRefs);
    }

    public Insight(String type, String key, Map<String, String> params, List<String> factRefs) {
        this(type, key, params, factRefs, Severity.INFO, 0, null, null, null, null, 0L);
    }

    public enum Severity {
        ALERT,
        ATTENTION,
        POSITIVE,
        INFO
    }

    public enum Basis {
        KNOWN_SCHEDULE,
        HISTORY,
        PROJECTION
    }

    public record Period(Kind kind, String value) {

        public Period {
            Objects.requireNonNull(kind, "kind");
            Objects.requireNonNull(value, "value");
        }

        public enum Kind {
            MONTH,
            DAY
        }
    }

    public record Confidence(int n, int window, String z) {
    }
}
