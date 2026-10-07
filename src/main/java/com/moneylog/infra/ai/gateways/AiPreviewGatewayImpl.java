package com.moneylog.infra.ai.gateways;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.ai.app.gateways.AiPreviewGateway;
import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.ai.domain.AiPreview;
import com.moneylog.core.ai.domain.AiPurpose;

/** Prévias de IA em JDBC. Toda consulta filtra pelo usuário. */
public class AiPreviewGatewayImpl implements AiPreviewGateway {

    private static final TypeReference<Map<String, String>> FACTS = new TypeReference<>() {
    };
    private static final TypeReference<Map<String, Integer>> COUNTS = new TypeReference<>() {
    };
    private static final TypeReference<List<String>> LIST = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public AiPreviewGatewayImpl(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public AiPreview save(AiPreview p) {
        jdbc.update("""
                INSERT INTO ai_previews (id, user_id, purpose, period, language, payload, payload_hash, facts, counts, redactions, estimated_tokens,
                    created_at, expires_at, used_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", p.id(), p.userId(), p.purpose().name(), p.period(), p.language(), p.payload(),
                p.payloadHash(), json(p.facts().values()), json(p.counts()), json(p.redactions()), p.estimatedTokens(), Timestamp.from(p.createdAt()),
                Timestamp.from(p.expiresAt()), p.usedAt() == null ? null : Timestamp.from(p.usedAt()));
        return p;
    }

    @Override
    public Optional<AiPreview> findById(UUID userId, UUID id) {
        return jdbc.query("SELECT * FROM ai_previews WHERE user_id = ? AND id = ?", (rs, i) -> map(rs), userId, id).stream().findFirst();
    }

    @Override
    public boolean markUsed(UUID userId, UUID id, Instant at) {
        return jdbc.update("UPDATE ai_previews SET used_at = ? WHERE user_id = ? AND id = ? AND used_at IS NULL", Timestamp.from(at), userId, id) > 0;
    }

    @Override
    public int deleteExpiredBefore(Instant before) {
        return jdbc.update("DELETE FROM ai_previews WHERE expires_at < ?", Timestamp.from(before));
    }

    private AiPreview map(ResultSet rs) throws SQLException {
        Timestamp used = rs.getTimestamp("used_at");
        return new AiPreview(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), AiPurpose.valueOf(rs.getString("purpose")),
                rs.getString("period"), rs.getString("language"), rs.getString("payload"), rs.getString("payload_hash").strip(),
                new AiFacts(read(rs.getString("facts"), FACTS)), read(rs.getString("counts"), COUNTS), read(rs.getString("redactions"), LIST),
                rs.getInt("estimated_tokens"), rs.getTimestamp("created_at").toInstant(), rs.getTimestamp("expires_at").toInstant(),
                used == null ? null : used.toInstant());
    }

    private String json(Object value) {
        try {
            return mapper.writeValueAsString(value);
        } catch (JacksonException e) {
            throw new IllegalStateException("dado de prévia não serializável", e);
        }
    }

    private <T> T read(String json, TypeReference<T> type) {
        try {
            return mapper.readValue(json, type);
        } catch (JacksonException e) {
            throw new IllegalStateException("dado de prévia ilegível", e);
        }
    }
}
