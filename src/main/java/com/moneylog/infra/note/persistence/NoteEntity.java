package com.moneylog.infra.note.persistence;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

/** Linha de {@code notes}. Toda gravação sobe {@code updatedAt} e, com ele, a versão (também ao trocar só os vínculos). */
@Entity
@Table(name = "notes")
public class NoteEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 255)
    private String path;

    @Column(name = "path_key", nullable = false, length = 512)
    private String pathKey;

    @Column(nullable = false, length = 255)
    private String title;

    @Column(nullable = false, columnDefinition = "text")
    private String body;

    @Column(name = "deleted_at")
    private Instant deletedAt;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected NoteEntity() {
    }

    public NoteEntity(UUID id, UUID userId, String path, String pathKey, String title, String body, Instant deletedAt, Instant now) {
        this.id = id;
        this.userId = userId;
        this.path = path;
        this.pathKey = pathKey;
        this.title = title;
        this.body = body;
        this.deletedAt = deletedAt;
        this.createdAt = now;
        this.updatedAt = now;
    }

    /** Aplica o estado do domínio; {@code updatedAt} sempre avança, então a versão sobe a cada gravação. */
    public void apply(String newPath, String newPathKey, String newTitle, String newBody, Instant newDeletedAt, Instant now) {
        this.path = newPath;
        this.pathKey = newPathKey;
        this.title = newTitle;
        this.body = newBody;
        this.deletedAt = newDeletedAt;
        this.updatedAt = now.isAfter(updatedAt) ? now : updatedAt.plus(1, ChronoUnit.MICROS);
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getPath() {
        return path;
    }

    public String getTitle() {
        return title;
    }

    public String getBody() {
        return body;
    }

    public Instant getDeletedAt() {
        return deletedAt;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public Long getVersion() {
        return version;
    }
}
