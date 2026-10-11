package com.moneylog.infra.note.gateways;

import java.sql.PreparedStatement;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;
import java.util.stream.Collectors;

import org.springframework.jdbc.core.BatchPreparedStatementSetter;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

import com.moneylog.core.note.app.Backlink;
import com.moneylog.core.note.app.NoteQuery;
import com.moneylog.core.note.app.NoteStatus;
import com.moneylog.core.note.app.NoteSummary;
import com.moneylog.core.note.app.StoredWikilink;
import com.moneylog.core.note.app.TagCount;
import com.moneylog.core.note.app.WikilinkUpdate;
import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.core.note.domain.LinkOrigin;
import com.moneylog.core.note.domain.Note;
import com.moneylog.core.note.domain.NoteLimits;
import com.moneylog.core.note.domain.NoteLink;
import com.moneylog.core.note.domain.NoteLinkType;
import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.NoteSnippets;
import com.moneylog.core.note.domain.WikilinkResolver;
import com.moneylog.core.note.exception.NoteExceptions.NoteSearchTimeoutException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.note.persistence.NoteEntity;
import com.moneylog.infra.note.persistence.NoteRepositoryJpa;

public class NoteGatewayImpl implements NoteGateway {

    private static final long LOCK_NAMESPACE = 0x6D6C6E6F74650000L;

    private static final String LINK_COLUMNS = "l.note_id, l.origin, l.target_type, l.target_note_id, l.target_transaction_id, l.target_account_id, "
            + "l.target_card_id, l.target_invoice_id, l.target_person_id, l.target_debt_id, l.target_goal_id, l.target_key, l.target_text, "
            + "l.alias, l.section, l.embed, l.context";

    private final NoteRepositoryJpa notes;
    private final NamedParameterJdbcTemplate named;
    private final JdbcTemplate jdbc;
    private final IdGenerator ids;
    private final TimeProvider time;

    public static final int SEARCH_TIMEOUT_SECONDS = 10;

    private final NamedParameterJdbcTemplate searchNamed;

    public NoteGatewayImpl(NoteRepositoryJpa notes, NamedParameterJdbcTemplate named, IdGenerator ids, TimeProvider time) {
        this(notes, named, ids, time, SEARCH_TIMEOUT_SECONDS);
    }

    public NoteGatewayImpl(NoteRepositoryJpa notes, NamedParameterJdbcTemplate named, IdGenerator ids, TimeProvider time, int searchTimeoutSeconds) {
        JdbcTemplate withTimeout = new JdbcTemplate(named.getJdbcTemplate().getDataSource());
        withTimeout.setQueryTimeout(searchTimeoutSeconds);
        this.searchNamed = new NamedParameterJdbcTemplate(withTimeout);
        this.notes = notes;
        this.named = named;
        this.jdbc = named.getJdbcTemplate();
        this.ids = ids;
        this.time = time;
    }

    @Override
    public void lockVault(UUID userId) {
        long key = LOCK_NAMESPACE ^ userId.getMostSignificantBits() ^ userId.getLeastSignificantBits();
        jdbc.queryForObject("SELECT pg_advisory_xact_lock(?)", Object.class, key);
    }

    @Override
    public Versioned<Note> save(Note note) {
        NoteEntity entity = notes.findByIdAndUserId(note.id(), note.userId()).orElse(null);
        Instant now = time.now();
        if (entity == null) {
            entity = NoteRepositoryMapper.toEntity(note, now);
        } else {
            entity.apply(note.path().value(), note.path().key(), note.title(), note.body(), note.trashedAt().orElse(null), now);
        }
        NoteEntity saved = notes.saveAndFlush(entity);
        jdbc.update("DELETE FROM note_tags WHERE note_id = ? AND user_id = ?", note.id(), note.userId());
        insertTags(note);
        jdbc.update("DELETE FROM note_links WHERE note_id = ? AND user_id = ?", note.id(), note.userId());
        insertLinks(note);
        return new Versioned<>(note, saved.getVersion(), saved.getCreatedAt(), saved.getUpdatedAt());
    }

    private void insertTags(Note note) {
        List<String> tags = new ArrayList<>(note.tags());
        if (tags.isEmpty()) {
            return;
        }
        java.sql.Timestamp stamp = java.sql.Timestamp.from(time.now());
        jdbc.batchUpdate("INSERT INTO note_tags (id, user_id, note_id, tag, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?)", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                ps.setObject(1, ids.next());
                ps.setObject(2, note.userId());
                ps.setObject(3, note.id());
                ps.setString(4, tags.get(i));
                ps.setTimestamp(5, stamp);
                ps.setTimestamp(6, stamp);
            }

            @Override
            public int getBatchSize() {
                return tags.size();
            }
        });
    }

    private void insertLinks(Note note) {
        List<NoteLink> links = note.links();
        if (links.isEmpty()) {
            return;
        }
        List<UUID> linkIds = links.stream().map(l -> ids.next()).toList();
        java.sql.Timestamp stamp = java.sql.Timestamp.from(time.now());
        jdbc.batchUpdate("""
                INSERT INTO note_links (id, user_id, note_id, position, origin, target_type, target_note_id, target_transaction_id,
                    target_account_id, target_card_id, target_invoice_id, target_person_id, target_debt_id, target_goal_id, target_key,
                    target_text, alias, section, embed, context, created_at, updated_at, target_name_key)
                VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)""", new BatchPreparedStatementSetter() {
            @Override
            public void setValues(PreparedStatement ps, int i) throws SQLException {
                NoteLink link = links.get(i);
                ps.setObject(1, linkIds.get(i));
                ps.setObject(2, note.userId());
                ps.setObject(3, note.id());
                ps.setInt(4, i);
                ps.setString(5, link.origin().name());
                ps.setString(6, link.type().name());
                for (NoteLinkType column : TARGET_COLUMNS) {
                    ps.setObject(7 + TARGET_COLUMNS.indexOf(column), link.type() == column ? link.targetId() : null);
                }
                ps.setString(15, link.targetKey());
                ps.setString(16, link.text());
                ps.setString(17, link.alias());
                ps.setString(18, link.section());
                ps.setBoolean(19, link.embed());
                ps.setString(20, link.context());
                ps.setTimestamp(21, stamp);
                ps.setTimestamp(22, stamp);
                ps.setString(23, link.origin() == LinkOrigin.WIKILINK ? WikilinkResolver.nameKey(link.text()) : null);
            }

            @Override
            public int getBatchSize() {
                return links.size();
            }
        });
    }

    private static final List<NoteLinkType> TARGET_COLUMNS = List.of(NoteLinkType.NOTE, NoteLinkType.TRANSACTION, NoteLinkType.ACCOUNT,
            NoteLinkType.CARD, NoteLinkType.INVOICE, NoteLinkType.PERSON, NoteLinkType.DEBT, NoteLinkType.GOAL);

    @Override
    public Optional<Versioned<Note>> findById(UUID userId, UUID noteId) {
        return notes.findByIdAndUserId(noteId, userId).map(e -> load(userId, List.of(e)).get(0));
    }

    @Override
    public List<Versioned<Note>> findByIds(UUID userId, Collection<UUID> noteIds) {
        if (noteIds.isEmpty()) {
            return List.of();
        }
        return load(userId, notes.findByUserIdAndIdIn(userId, noteIds));
    }

    private List<Versioned<Note>> load(UUID userId, List<NoteEntity> entities) {
        if (entities.isEmpty()) {
            return List.of();
        }
        List<UUID> idList = entities.stream().map(NoteEntity::getId).toList();
        MapSqlParameterSource params = new MapSqlParameterSource("uid", userId).addValue("ids", idList);
        Map<UUID, List<String>> tags = new HashMap<>();
        named.query("SELECT note_id, tag FROM note_tags WHERE user_id = :uid AND note_id IN (:ids) ORDER BY tag", params, rs -> {
            tags.computeIfAbsent(rs.getObject("note_id", UUID.class), k -> new ArrayList<>()).add(rs.getString("tag"));
        });
        Map<UUID, List<NoteLink>> links = new HashMap<>();
        named.query("SELECT " + LINK_COLUMNS + " FROM note_links l WHERE l.user_id = :uid AND l.note_id IN (:ids) ORDER BY l.note_id, l.position",
                params, rs -> {
                    links.computeIfAbsent(rs.getObject("note_id", UUID.class), k -> new ArrayList<>()).add(toLink(rs));
                });
        return entities.stream().map(e -> NoteRepositoryMapper.toVersioned(e, tags.getOrDefault(e.getId(), List.of()),
                links.getOrDefault(e.getId(), List.of()))).toList();
    }

    private static NoteLink toLink(ResultSet rs) throws SQLException {
        UUID target = null;
        for (String column : new String[] {"target_note_id", "target_transaction_id", "target_account_id", "target_card_id", "target_invoice_id",
                "target_person_id", "target_debt_id", "target_goal_id"}) {
            UUID value = rs.getObject(column, UUID.class);
            if (value != null) {
                target = value;
            }
        }
        String key = rs.getString("target_key");
        return new NoteLink(LinkOrigin.valueOf(rs.getString("origin")), NoteLinkType.valueOf(rs.getString("target_type")), target,
                key == null ? null : key.strip(), rs.getString("target_text"), rs.getString("alias"), rs.getString("section"), rs.getBoolean("embed"),
                rs.getString("context"));
    }

    @Override
    public List<WikilinkResolver.Entry> activeIndex(UUID userId) {
        return jdbc.query("SELECT id, path FROM notes WHERE user_id = ? AND deleted_at IS NULL",
                (rs, row) -> new WikilinkResolver.Entry(rs.getObject("id", UUID.class), NotePath.of(rs.getString("path"))), userId);
    }

    @Override
    public Map<UUID, String> trashedPaths(UUID userId) {
        Map<UUID, String> out = new HashMap<>();
        jdbc.query("SELECT id, path FROM notes WHERE user_id = ? AND deleted_at IS NOT NULL", rs -> {
            out.put(rs.getObject("id", UUID.class), rs.getString("path"));
        }, userId);
        return out;
    }

    @Override
    public int countAll(UUID userId) {
        return (int) notes.countByUserId(userId);
    }

    @Override
    public List<StoredWikilink> wikilinksByName(UUID userId, Collection<String> nameKeys) {
        return jdbc.query("""
                SELECT id, note_id, target_text, target_note_id, target_name_key FROM note_links
                WHERE user_id = ? AND origin = 'WIKILINK' AND (target_name_key = ANY (?) OR target_name_key IS NULL)""", ps -> {
            ps.setObject(1, userId);
            ps.setArray(2, ps.getConnection().createArrayOf("varchar", nameKeys.toArray()));
        }, (rs, row) -> new StoredWikilink(rs.getObject("id", UUID.class), rs.getObject("note_id", UUID.class), rs.getString("target_text"),
                rs.getObject("target_note_id", UUID.class), rs.getString("target_name_key")));
    }

    @Override
    public void updateWikilinks(UUID userId, List<WikilinkUpdate> updates) {
        jdbc.batchUpdate("UPDATE note_links SET target_note_id = ?, target_name_key = ? WHERE id = ? AND user_id = ? AND origin = 'WIKILINK'",
                new BatchPreparedStatementSetter() {
                    @Override
                    public void setValues(PreparedStatement ps, int i) throws SQLException {
                        ps.setObject(1, updates.get(i).targetNoteId());
                        ps.setString(2, updates.get(i).nameKey());
                        ps.setObject(3, updates.get(i).linkId());
                        ps.setObject(4, userId);
                    }

                    @Override
                    public int getBatchSize() {
                        return updates.size();
                    }
                });
    }

    @Override
    public List<UUID> noteIdsLinkingTo(UUID userId, UUID targetNoteId) {
        return jdbc.queryForList("SELECT DISTINCT note_id FROM note_links WHERE user_id = ? AND origin = 'WIKILINK' AND target_note_id = ?",
                UUID.class, userId, targetNoteId);
    }

    @Override
    public Slice<NoteSummary> search(NoteQuery q) {
        MapSqlParameterSource params = new MapSqlParameterSource("uid", q.userId());
        StringBuilder where = new StringBuilder(" WHERE n.user_id = :uid AND n.deleted_at IS ")
                .append(q.status() == NoteStatus.TRASHED ? "NOT NULL" : "NULL");
        String select = "SELECT n.id, n.path, n.title, n.updated_at, n.deleted_at, n.version";
        if (q.q() != null) {
            params.addValue("pat", "%" + escapeLike(q.q()) + "%");
            select += ", CASE WHEN unaccent(n.title) ILIKE unaccent(:pat) THEN 3 WHEN unaccent(n.path) ILIKE unaccent(:pat) THEN 2 "
                    + "WHEN unaccent(n.body) ILIKE unaccent(:pat) THEN 1 ELSE 0 END AS score, "
                    + "CASE WHEN strpos(lower(unaccent(n.body)), lower(unaccent(:qraw))) > 0 THEN substr(n.body, "
                    + "GREATEST(strpos(lower(unaccent(n.body)), lower(unaccent(:qraw))) - 120, 1), 480) ELSE substr(n.body, 1, 480) END AS snip";
            params.addValue("qraw", q.q());
            where.append(" AND (unaccent(n.title) ILIKE unaccent(:pat) OR unaccent(n.path) ILIKE unaccent(:pat) OR unaccent(n.body) ILIKE unaccent(:pat))");
        }
        if (q.tag() != null) {
            params.addValue("tag", q.tag());
            where.append(" AND EXISTS (SELECT 1 FROM note_tags t WHERE t.note_id = n.id AND t.user_id = n.user_id AND t.tag = :tag)");
        }
        if (q.folder() != null && !q.folder().isBlank()) {
            String folder = java.text.Normalizer.normalize(q.folder().strip().replaceAll("^/+|/+$", ""), java.text.Normalizer.Form.NFC)
                    .toLowerCase(Locale.ROOT);
            params.addValue("folder", escapeLike(folder) + "/%");
            where.append(" AND n.path_key LIKE :folder");
        }
        if (q.linkedType() != null) {
            where.append(linkedClause(q, params));
        }
        String order = switch (q.sort()) {
            case RELEVANCE -> q.q() != null ? " ORDER BY score DESC, n.updated_at DESC, n.id" : " ORDER BY n.updated_at DESC, n.id";
            case UPDATED -> " ORDER BY n.updated_at DESC, n.id";
            case PATH -> " ORDER BY n.path_key, n.id";
            case TITLE -> " ORDER BY lower(n.title), n.id";
        };
        PageQuery page = q.page();
        params.addValue("limit", page.fetchSize()).addValue("offset", page.offset());
        String sql = select + " FROM notes n" + where + order + " LIMIT :limit OFFSET :offset";
        List<Object[]> rows;
        try {
            rows = searchNamed.query(sql, params, (rs, row) -> new Object[] {rs.getObject("id", UUID.class), rs.getString("path"),
                rs.getString("title"), rs.getTimestamp("updated_at").toInstant(),
                rs.getTimestamp("deleted_at") == null ? null : rs.getTimestamp("deleted_at").toInstant(), rs.getLong("version"),
                q.q() == null ? null : rs.getString("snip")});
        } catch (org.springframework.dao.QueryTimeoutException timeout) {
            throw new NoteSearchTimeoutException();
        }
        List<UUID> pageIds = rows.stream().map(r -> (UUID) r[0]).toList();
        Map<UUID, List<String>> tags = new HashMap<>();
        if (!pageIds.isEmpty()) {
            named.query("SELECT note_id, tag FROM note_tags WHERE user_id = :uid AND note_id IN (:ids) ORDER BY tag",
                    new MapSqlParameterSource("uid", q.userId()).addValue("ids", pageIds),
                    rs -> {
                        tags.computeIfAbsent(rs.getObject("note_id", UUID.class), k -> new ArrayList<>()).add(rs.getString("tag"));
                    });
        }
        List<NoteSummary> items = rows.stream().map(r -> new NoteSummary((UUID) r[0], (String) r[1], (String) r[2],
                tags.getOrDefault((UUID) r[0], List.of()), r[6] == null ? null : NoteSnippets.aroundTerm((String) r[6], q.q(), NoteLimits.SNIPPET_MAX),
                (Instant) r[3], (Instant) r[4], (Long) r[5])).toList();
        return Slice.fromOverfetch(items, page);
    }

    private static String linkedClause(NoteQuery q, MapSqlParameterSource params) {
        String column = switch (q.linkedType()) {
            case NOTE -> "target_note_id";
            case TRANSACTION -> "target_transaction_id";
            case ACCOUNT -> "target_account_id";
            case CARD -> "target_card_id";
            case INVOICE -> "target_invoice_id";
            case PERSON -> "target_person_id";
            case DEBT -> "target_debt_id";
            case GOAL -> "target_goal_id";
            case MONTHLY_SUMMARY -> "target_key";
        };
        params.addValue("linked", q.linkedType().usesKey() ? q.linkedKey() : q.linkedId());
        return " AND EXISTS (SELECT 1 FROM note_links lk WHERE lk.note_id = n.id AND lk.user_id = n.user_id AND lk." + column + " = :linked)";
    }

    static String escapeLike(String text) {
        return text.replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_");
    }

    @Override
    public Slice<Backlink> backlinks(UUID userId, UUID noteId, PageQuery page) {
        List<Backlink> rows = jdbc.query("""
                SELECT * FROM (
                    SELECT DISTINCT ON (n.id) n.id, n.path, n.title, l.context
                    FROM note_links l JOIN notes n ON n.id = l.note_id AND n.user_id = l.user_id
                    WHERE l.user_id = ? AND l.target_note_id = ? AND n.deleted_at IS NULL AND n.id <> ?
                    ORDER BY n.id, l.origin DESC, l.position) b
                ORDER BY lower(b.path), b.id LIMIT ? OFFSET ?""",
                (rs, row) -> new Backlink(rs.getObject("id", UUID.class), rs.getString("path"), rs.getString("title"), rs.getString("context")),
                userId, noteId, noteId, page.fetchSize(), page.offset());
        return Slice.fromOverfetch(rows, page);
    }

    @Override
    public List<TagCount> tagCounts(UUID userId) {
        return jdbc.query("""
                SELECT t.tag, count(*) AS total FROM note_tags t JOIN notes n ON n.id = t.note_id AND n.user_id = t.user_id
                WHERE t.user_id = ? AND n.deleted_at IS NULL GROUP BY t.tag ORDER BY total DESC, t.tag""",
                (rs, row) -> new TagCount(rs.getString("tag"), rs.getLong("total")), userId);
    }

    @Override
    public int countPendingLinks(UUID userId, Collection<UUID> noteIds) {
        if (noteIds.isEmpty()) {
            return 0;
        }
        Integer count = named.queryForObject("""
                SELECT count(*) FROM note_links WHERE user_id = :uid AND note_id IN (:ids)
                AND ((origin = 'WIKILINK' AND target_note_id IS NULL) OR (origin = 'EXPLICIT' AND target_text IS NOT NULL))""",
                new MapSqlParameterSource("uid", userId).addValue("ids", noteIds), Integer.class);
        return count == null ? 0 : count;
    }

    @Override
    public int deleteTrashed(UUID userId) {
        jdbc.update("""
                DELETE FROM note_links WHERE user_id = ? AND target_note_id IN
                    (SELECT id FROM notes WHERE user_id = ? AND deleted_at IS NOT NULL)""", userId, userId);
        return jdbc.update("DELETE FROM notes WHERE user_id = ? AND deleted_at IS NOT NULL", userId);
    }

    @Override
    public List<Versioned<Note>> findActiveBatch(UUID userId, UUID afterId, int limit) {
        List<UUID> batchIds = afterId == null
                ? jdbc.queryForList("SELECT id FROM notes WHERE user_id = ? AND deleted_at IS NULL ORDER BY id LIMIT ?", UUID.class, userId, limit)
                : jdbc.queryForList("SELECT id FROM notes WHERE user_id = ? AND deleted_at IS NULL AND id > ? ORDER BY id LIMIT ?", UUID.class,
                        userId, afterId, limit);
        Map<UUID, Versioned<Note>> byId = findByIds(userId, batchIds).stream()
                .collect(Collectors.toMap(v -> v.value().id(), v -> v, (a, b) -> a, LinkedHashMap::new));
        return batchIds.stream().map(byId::get).filter(java.util.Objects::nonNull).toList();
    }
}
