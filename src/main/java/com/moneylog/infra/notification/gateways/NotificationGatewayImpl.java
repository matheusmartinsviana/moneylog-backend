package com.moneylog.infra.notification.gateways;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.util.HashSet;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import tools.jackson.core.JacksonException;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.notification.app.gateways.NotificationGateway;
import com.moneylog.core.notification.domain.Notification;
import com.moneylog.core.notification.domain.NotificationDraft;
import com.moneylog.core.notification.domain.NotificationKind;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public class NotificationGatewayImpl implements NotificationGateway {

    private static final String COLUMNS = "id, user_id, kind, ref_type, ref_id, on_date, title_key, params, due_on, push_eligible, push_sent_at, "
            + "read_at, archived, created_at";
    private static final TypeReference<Map<String, String>> PARAMS = new TypeReference<>() {
    };

    private final JdbcTemplate jdbc;
    private final ObjectMapper mapper;

    public NotificationGatewayImpl(JdbcTemplate jdbc, ObjectMapper mapper) {
        this.jdbc = jdbc;
        this.mapper = mapper;
    }

    @Override
    public boolean insertIfAbsent(Notification n) {
        NotificationDraft d = n.draft();
        return jdbc.update("""
                INSERT INTO notifications (id, user_id, kind, ref_type, ref_id, on_date, title_key, params, due_on, push_eligible, push_sent_at,
                    read_at, archived, created_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, NULL, NULL, FALSE, ?)
                ON CONFLICT (user_id, kind, ref_id, on_date) DO NOTHING""", n.id(), n.userId(), d.kind().name(), d.refType(), d.refId(), d.onDate(),
                d.titleKey(), json(d.params()), d.dueOn(), n.pushEligible(), Timestamp.from(n.createdAt())) > 0;
    }

    @Override
    public Slice<Notification> list(UUID userId, boolean unreadOnly, NotificationKind kind, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        List<Notification> rows = jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE user_id = ? AND archived = FALSE "
                        + "AND (? = FALSE OR read_at IS NULL) AND (?::text IS NULL OR kind = ?) ORDER BY created_at DESC, id LIMIT ? OFFSET ?",
                (rs, i) -> map(rs), userId, unreadOnly, kind == null ? null : kind.name(), kind == null ? null : kind.name(), page.fetchSize(),
                page.offset());
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public long unreadCount(UUID userId) {
        Long count = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND archived = FALSE AND read_at IS NULL", Long.class,
                userId);
        return count == null ? 0 : count;
    }

    @Override
    public Optional<Notification> find(UUID userId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE user_id = ? AND id = ?", (rs, i) -> map(rs), userId, id).stream().findFirst();
    }

    @Override
    public void save(Notification n) {
        jdbc.update("UPDATE notifications SET read_at = ?, archived = ?, push_sent_at = ? WHERE user_id = ? AND id = ?", ts(n.readAt()), n.archived(),
                ts(n.pushSentAt()), n.userId(), n.id());
    }

    @Override
    public int markAllRead(UUID userId, Instant now) {
        return jdbc.update("UPDATE notifications SET read_at = ? WHERE user_id = ? AND archived = FALSE AND read_at IS NULL", Timestamp.from(now), userId);
    }

    @Override
    public Set<String> identitiesOn(UUID userId, LocalDate day) {
        return new HashSet<>(jdbc.query("SELECT kind, ref_id, on_date FROM notifications WHERE user_id = ? AND on_date = ?",
                (rs, i) -> rs.getString("kind") + "|" + rs.getObject("ref_id", UUID.class) + "|" + rs.getObject("on_date", LocalDate.class), userId,
                day));
    }

    @Override
    public int countOn(UUID userId, LocalDate day) {
        Integer count = jdbc.queryForObject("SELECT count(*) FROM notifications WHERE user_id = ? AND on_date = ?", Integer.class, userId, day);
        return count == null ? 0 : count;
    }

    @Override
    public List<Notification> pushPending(int limit) {
        return jdbc.query("SELECT " + COLUMNS + " FROM notifications WHERE push_eligible AND push_sent_at IS NULL AND archived = FALSE "
                + "AND created_at > now() - interval '2 days' ORDER BY created_at, id LIMIT ?", (rs, i) -> map(rs), limit);
    }

    @Override
    public int archiveReadBefore(Instant cutoff) {
        return jdbc.update("UPDATE notifications SET archived = TRUE WHERE archived = FALSE AND read_at IS NOT NULL AND read_at < ?",
                Timestamp.from(cutoff));
    }

    @Override
    public List<UUID> allUserIds() {
        return jdbc.query("SELECT id FROM users ORDER BY id", (rs, i) -> rs.getObject("id", UUID.class));
    }

    private Notification map(ResultSet rs) throws SQLException {
        NotificationDraft draft = new NotificationDraft(NotificationKind.valueOf(rs.getString("kind")), rs.getString("ref_type"),
                rs.getObject("ref_id", UUID.class), rs.getObject("on_date", LocalDate.class), rs.getString("title_key"),
                parse(rs.getString("params")), rs.getObject("due_on", LocalDate.class));
        return Notification.restore(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), draft,
                rs.getTimestamp("created_at").toInstant(), rs.getBoolean("push_eligible"), instant(rs.getTimestamp("read_at")),
                rs.getBoolean("archived"), instant(rs.getTimestamp("push_sent_at")));
    }

    private String json(Map<String, String> params) {
        try {
            return mapper.writeValueAsString(params);
        } catch (JacksonException e) {
            throw new IllegalStateException("parâmetros de notificação não serializáveis", e);
        }
    }

    private Map<String, String> parse(String json) {
        try {
            return mapper.readValue(json, PARAMS);
        } catch (JacksonException e) {
            throw new IllegalStateException("parâmetros de notificação ilegíveis", e);
        }
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }

    private static Instant instant(Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
