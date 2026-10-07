package com.moneylog.infra.notification.gateways;

import java.sql.ResultSet;
import java.sql.SQLException;
import java.sql.Timestamp;
import java.time.Instant;
import java.time.LocalDate;
import java.time.LocalTime;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.JdbcTemplate;

import com.moneylog.core.notification.app.gateways.ReminderGateway;
import com.moneylog.core.notification.domain.Reminder;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

public class ReminderGatewayImpl implements ReminderGateway {

    private static final String COLUMNS = "id, user_id, title, remind_on, remind_at, note, link_type, link_id, done_at, created_at";

    private final JdbcTemplate jdbc;

    public ReminderGatewayImpl(JdbcTemplate jdbc) {
        this.jdbc = jdbc;
    }

    @Override
    public Reminder save(Reminder r) {
        jdbc.update("""
                INSERT INTO reminders (id, user_id, title, remind_on, remind_at, note, link_type, link_id, done_at, created_at, updated_at)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, now())
                ON CONFLICT (id) DO UPDATE SET title = EXCLUDED.title, remind_on = EXCLUDED.remind_on, remind_at = EXCLUDED.remind_at,
                    note = EXCLUDED.note, link_type = EXCLUDED.link_type, link_id = EXCLUDED.link_id, done_at = EXCLUDED.done_at, updated_at = now()
                WHERE reminders.user_id = EXCLUDED.user_id""", r.id(), r.userId(), r.title(), r.remindOn(), r.remindAt(),
                r.note(), r.linkType(), r.linkId(), ts(r.doneAt()), Timestamp.from(r.createdAt()));
        return r;
    }

    @Override
    public Optional<Reminder> find(UUID userId, UUID id) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reminders WHERE user_id = ? AND id = ?", (rs, i) -> map(rs), userId, id).stream().findFirst();
    }

    @Override
    public Slice<Reminder> list(UUID userId, boolean includeDone, PageQuery page) {
        if (page.isBeyondAnyData()) {
            return Slice.empty();
        }
        List<Reminder> rows = jdbc.query("SELECT " + COLUMNS + " FROM reminders WHERE user_id = ? AND (? OR done_at IS NULL) "
                        + "ORDER BY (done_at IS NOT NULL), remind_on, remind_at NULLS FIRST, id LIMIT ? OFFSET ?", (rs, i) -> map(rs), userId, includeDone,
                page.fetchSize(), page.offset());
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public List<Reminder> openUntil(UUID userId, LocalDate day) {
        return jdbc.query("SELECT " + COLUMNS + " FROM reminders WHERE user_id = ? AND done_at IS NULL AND remind_on <= ? ORDER BY remind_on, id",
                (rs, i) -> map(rs), userId, day);
    }

    private static Reminder map(ResultSet rs) throws SQLException {
        LocalTime time = rs.getObject("remind_at", LocalTime.class);
        Timestamp done = rs.getTimestamp("done_at");
        return Reminder.restore(rs.getObject("id", UUID.class), rs.getObject("user_id", UUID.class), rs.getString("title"),
                rs.getObject("remind_on", LocalDate.class), time, rs.getString("note"), rs.getString("link_type"), rs.getObject("link_id", UUID.class),
                rs.getTimestamp("created_at").toInstant(), done == null ? null : done.toInstant());
    }

    private static Timestamp ts(Instant instant) {
        return instant == null ? null : Timestamp.from(instant);
    }
}
