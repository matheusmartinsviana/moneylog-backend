package com.moneylog.core.notification.domain;

import java.time.LocalDate;
import java.util.Map;
import java.util.Objects;
import java.util.TreeMap;
import java.util.UUID;

/**
 * Notificação a gerar. O texto é uma <b>chave de i18n + parâmetros</b>: o backend não grava frase pronta (S-34). A identidade
 * {@code (kind, refId, onDate)} é a chave de idempotência (S-174); {@code refId} nunca é nulo (no Postgres NULLs não colidem em índice único).
 *
 * @param refType tipo da entidade referida ({@code CARD}, {@code DEBT}, {@code RECURRENCE}, {@code GOAL}, {@code REMINDER}, {@code USER})
 * @param dueOn   data do evento (vencimento, prazo), se houver
 */
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
