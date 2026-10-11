package com.moneylog.infra.ai.gateways;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.ai.app.gateways.AiAnalysisGateway;
import com.moneylog.core.ai.domain.AiAnalysis;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public class AiAnalysisGatewayImpl implements AiAnalysisGateway {

    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AiAnalysisGatewayImpl(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public AiAnalysis save(AiAnalysis a) {
        jdbc.update("""
                INSERT INTO ai_analyses (id, user_id, preview_id, idempotency_key, purpose, period, provider, model, status, redacted_payload,
                    payload_hash, input_tokens, output_tokens, duration_ms, result_text, caveat, fact_refs, failure_reason, created_at, payload_purged_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", a.id(), a.userId(), a.previewId(), a.idempotencyKey(),
                a.purpose().name(), a.period(), a.provider(), a.model() == null ? "" : a.model(), a.status().name(), a.redactedPayload(),
                a.payloadHash(), a.inputTokens(), a.outputTokens(), a.durationMs(), a.resultText(), a.caveat(), json(a.factRefs()), a.failureReason(),
                Timestamp.from(a.createdAt()), a.payloadPurgedAt() == null ? null : Timestamp.from(a.payloadPurgedAt()));
        return a;
    }

    @Override
    public Optional<AiAnalysis> findById(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM ai_analyses WHERE user_id = ? AND id = ?", (rs, i) -> map(rs), userId, id).stream().findFirst();
    }

    @Override
    public Optional<AiAnalysis> findByIdempotencyKey(UUID userId, UUID key) {
        return jdbc.query("SELECT * FROM ai_analyses WHERE user_id = ? AND idempotency_key = ?", (rs, i) -> map(rs), userId, key).stream().findFirst();
    }

    @Override
    public Slice<AiAnalysis> list(UUID userId, AiPurpose purpose, AiStatus status, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        StringBuilder sql = new StringBuilder("SELECT * FROM ai_analyses WHERE user_id = ?");
        List<Object> args = new ArrayList<>(List.of(userId));
        if (purpose != null) {
            sql.append(" AND purpose = ?");
            args.add(purpose.name());
        }
        if (status != null) {
            sql.append(" AND status = ?");
            args.add(status.name());
        }
        sql.append(" ORDER BY created_at DESC, id DESC LIMIT ? OFFSET ?");
        args.add(page.fetchSize());
        args.add(page.offset());
        return Slice.fromOverfetch(jdbc.query(sql.toString(), (rs, i) -> map(rs), args.toArray()), page);
    }

    @Override
    public long countProviderCalls(UUID userId, Instant from, Instant to) {
        Long count = jdbc.queryForObject("SELECT COUNT(*) FROM ai_analyses WHERE user_id = ? AND created_at >= ? AND created_at < ? "
                + "AND status IN ('COMPLETED', 'FAILED', 'REJECTED_UNGROUNDED')", Long.class, userId, Timestamp.from(from), Timestamp.from(to));
        return count == null ? 0 : count;
    }

    @Override
    public long sumTokens(UUID userId, Instant from, Instant to) {
        Long sum = jdbc.queryForObject("SELECT COALESCE(SUM(input_tokens + output_tokens), 0) FROM ai_analyses WHERE user_id = ? "
                + "AND created_at >= ? AND created_at < ?", Long.class, userId, Timestamp.from(from), Timestamp.from(to));
        return sum == null ? 0 : sum;
    }

    @Override
    public int purgePayloadsBefore(Instant before, Instant purgedAt) {
        return jdbc.update("UPDATE ai_analyses SET redacted_payload = NULL, payload_purged_at = ? WHERE redacted_payload IS NOT NULL AND created_at < ?",
                Timestamp.from(purgedAt), Timestamp.from(before));
    }

    private AiAnalysis map(ResultSet rs) throws SQLException {
        Timestamp purged = rs.getTimestamp("payload_purged_at");
        String hash = rs.getString("payload_hash");
        return new AiAnalysis(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getObject("preview_id", UUID.class),
                rs.getObject("idempotency_key", UUID.class), AiPurpose.valueOf(rs.getString("purpose")), rs.getString("period"),
                rs.getString("provider"), rs.getString("model"), AiStatus.valueOf(rs.getString("status")), rs.getString("redacted_payload"),
                hash == null ? null : hash.strip(), rs.getInt("input_tokens"), rs.getInt("output_tokens"), rs.getLong("duration_ms"),
                rs.getString("result_text"), rs.getString("caveat"), read(rs.getString("fact_refs")), rs.getString("failure_reason"),
                rs.getTimestamp("created_at").toInstant(), purged == null ? null : purged.toInstant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("dado de análise não serializável", e);
        }
    }

    private List<String> read(String json) {
        try {
            return mapper.readValue(json, LIST);
        } catch (JacksonException e) {
            throw new IllegalStateException("dado de análise ilegível", e);
        }
    }
}
