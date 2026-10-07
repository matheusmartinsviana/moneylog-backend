package com.moneylog.infra.note.gateways;

import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.moneylog.core.note.app.gateways.NoteTargetGateway;
import com.moneylog.core.note.domain.NoteLinkType;

/**
 * Lê rótulo e estado dos alvos de vínculo, uma consulta por tipo e sempre filtrada pelo usuário. Só {@code SELECT}: o vault nunca grava nem
 * trava nenhuma entidade financeira.
 */
public class NoteTargetGatewayImpl implements NoteTargetGateway {

    private final NamedParameterJdbcTemplate named;

    public NoteTargetGatewayImpl(NamedParameterJdbcTemplate named) {
        this.named = named;
    }

    @Override
    public Map<TargetRef, TargetInfo> describe(UUID userId, Collection<TargetRef> refs) {
        Map<NoteLinkType, List<UUID>> byType = refs.stream().filter(r -> r.id() != null && !r.type().usesKey())
                .collect(Collectors.groupingBy(TargetRef::type, java.util.LinkedHashMap::new, Collectors.mapping(TargetRef::id, Collectors.toList())));
        Map<TargetRef, TargetInfo> out = new HashMap<>();
        byType.forEach((type, ids) -> named.query(sqlFor(type), new MapSqlParameterSource("uid", userId).addValue("ids", ids), rs -> {
            String status = rs.getString("status");
            out.put(new TargetRef(type, rs.getObject("id", UUID.class)),
                    new TargetInfo(rs.getString("label"), status == null ? null : TargetStatus.valueOf(status)));
        }));
        return out;
    }

    private static String sqlFor(NoteLinkType type) {
        return switch (type) {
            case TRANSACTION -> "SELECT id, COALESCE(NULLIF(btrim(description), ''), type) AS label, status FROM transactions WHERE user_id = :uid AND id IN (:ids)";
            case ACCOUNT -> "SELECT id, name AS label, status FROM accounts WHERE user_id = :uid AND id IN (:ids)";
            case CARD -> "SELECT id, name AS label, status FROM credit_cards WHERE user_id = :uid AND id IN (:ids)";
            case INVOICE -> "SELECT i.id, c.name || ' · ' || i.reference_month AS label, NULL AS status FROM credit_card_invoices i "
                    + "JOIN credit_cards c ON c.id = i.card_id AND c.user_id = i.user_id WHERE i.user_id = :uid AND i.id IN (:ids)";
            case PERSON -> "SELECT id, name AS label, CASE WHEN archived THEN 'ARCHIVED' ELSE 'ACTIVE' END AS status FROM persons "
                    + "WHERE user_id = :uid AND id IN (:ids)";
            case DEBT -> "SELECT d.id, COALESCE(NULLIF(btrim(d.description), ''), p.name) AS label, COALESCE(d.closure, 'ACTIVE') AS status "
                    + "FROM debts d JOIN persons p ON p.id = d.person_id AND p.user_id = d.user_id WHERE d.user_id = :uid AND d.id IN (:ids)";
            case GOAL -> "SELECT id, name AS label, CASE WHEN archived THEN 'ARCHIVED' ELSE 'ACTIVE' END AS status FROM goals "
                    + "WHERE user_id = :uid AND id IN (:ids)";
            case NOTE -> "SELECT id, title AS label, CASE WHEN deleted_at IS NULL THEN 'ACTIVE' ELSE 'TRASHED' END AS status FROM notes "
                    + "WHERE user_id = :uid AND id IN (:ids)";
            case MONTHLY_SUMMARY -> throw new IllegalArgumentException("resumo mensal não tem id");
        };
    }
}
