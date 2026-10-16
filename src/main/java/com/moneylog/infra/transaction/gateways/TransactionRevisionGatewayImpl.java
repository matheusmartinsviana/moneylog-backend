package com.moneylog.infra.transaction.gateways;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DuplicateKeyException;
import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionState;

public class TransactionRevisionGatewayImpl implements TransactionRevisionGateway {

    private final JdbcTemplate jdbc;
    private final ObjectMapper objectMapper;

    public TransactionRevisionGatewayImpl(JdbcTemplate jdbc, ObjectMapper objectMapper) {
        this.jdbc = jdbc;
        this.objectMapper = objectMapper;
    }

    @Override
    public void save(UUID userId, Transaction previous, int revision, Instant at) {
        save(userId, previous, revision, at, null, null);
    }

    @Override
    public void save(UUID userId, Transaction previous, int revision, Instant at, String change, String reason) {
        try {
            insert(userId, previous, revision, at, change, reason);
        } catch (DuplicateKeyException e) {
            throw new VersionConflictException();
        }
    }

    private void insert(UUID userId, Transaction previous, int revision, Instant at, String change, String reason) {
        jdbc.update("""
                INSERT INTO transaction_revisions (id, user_id, transaction_id, revision, snapshot, edited_at, created_at, updated_at)
                VALUES (gen_random_uuid(), ?, ?, ?, CAST(? AS jsonb), ?, ?, ?)""",
                userId, previous.id(), revision, snapshot(previous, change, reason), Timestamp.from(at), Timestamp.from(at), Timestamp.from(at));
    }

    static Map<String, Object> fields(Transaction transaction) {
        TransactionState s = transaction.state();
        Map<String, Object> fields = new LinkedHashMap<>();
        fields.put("id", s.id().toString());
        fields.put("type", s.type().name());
        fields.put("status", s.status().name());
        fields.put("amount", s.amount().toPlainString());
        fields.put("currency", s.amount().currency().value());
        fields.put("date", s.date().toString());
        fields.put("description", s.description());
        fields.put("notes", s.notes());
        fields.put("method", s.paymentMethod() == null ? null : s.paymentMethod().name());
        fields.put("accountId", s.accountId() == null ? null : s.accountId().toString());
        fields.put("counterpartAccountId", s.counterpartAccountId() == null ? null : s.counterpartAccountId().toString());
        fields.put("categoryId", s.categoryId() == null ? null : s.categoryId().toString());
        fields.put("refundOfId", s.refundOfId() == null ? null : s.refundOfId().toString());
        fields.put("investmentDirection", s.investmentDirection() == null ? null : s.investmentDirection().name());
        fields.put("adjustmentDirection", s.adjustmentDirection() == null ? null : s.adjustmentDirection().name());
        fields.put("adjustmentKind", s.adjustmentKind() == null ? null : s.adjustmentKind().name());
        fields.put("reason", s.reason());
        if (s.incomeCompetence() != null) {
            fields.put("incomeCompetence", s.incomeCompetence().toString());
        }
        if (s.purchaseShares() != null) {
            fields.put("splitMode", s.purchaseShares().mode().name());
            fields.put("personShares", s.purchaseShares().lines().stream().map(line -> {
                Map<String, Object> part = new LinkedHashMap<>();
                part.put("personId", line.personId() == null ? null : line.personId().toString());
                part.put("bps", line.bps());
                part.put("amount", line.amount() == null ? null : line.amount().toPlainString());
                return part;
            }).toList());
        }
        fields.put("version", s.version());
        return fields;
    }

    private String snapshot(Transaction transaction, String change, String reason) {
        Map<String, Object> fields = fields(transaction);
        if (change != null) {
            fields.put("change", change);
            fields.put("changeReason", reason);
        }
        return objectMapper.writeValueAsString(fields);
    }
}
