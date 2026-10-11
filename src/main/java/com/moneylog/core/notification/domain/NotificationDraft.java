package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

public record NotificationDraft(NotificationKind kind, String refType, UUID refId, LocalDate onDate, String titleKey, Map<String, String> params,
                                LocalDate dueOn) {

    public NotificationDraft {
        Objects.requireNonNull(kind, "kind");
        Objects.requireNonNull(refType, "refType");
        Objects.requireNonNull(refId, "refId");
        Objects.requireNonNull(onDate, "onDate");
        Objects.requireNonNull(titleKey, "titleKey");
        params = Map.copyOf(new TreeMap<>(params));
    }

    public String identity() {
        return kind + "|" + refId + "|" + onDate;
    }
}
