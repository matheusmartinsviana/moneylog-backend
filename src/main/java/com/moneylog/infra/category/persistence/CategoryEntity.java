package com.moneylog.infra.category.persistence;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.category.domain.CategoryKind;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "categories")
public class CategoryEntity {

    @Id
    private UUID id;

    @Column(name = "user_id", nullable = false, updatable = false)
    private UUID userId;

    @Column(nullable = false, length = 60)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16, updatable = false)
    private CategoryKind kind;

    @Column(name = "system", nullable = false, updatable = false)
    private boolean systemCategory;

    @Column(name = "system_key", length = 40, updatable = false)
    private String systemKey;

    @Column(nullable = false)
    private boolean archived;

    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    protected CategoryEntity() {
    }

    public CategoryEntity(UUID id, UUID userId, String name, CategoryKind kind, boolean systemCategory, String systemKey,
                          boolean archived, Instant now) {
        this.id = id;
        this.userId = userId;
        this.name = name;
        this.kind = kind;
        this.systemCategory = systemCategory;
        this.systemKey = systemKey;
        this.archived = archived;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public void apply(String newName, boolean newArchived, Instant now) {
        if (!name.equals(newName) || archived != newArchived) {
            this.name = newName;
            this.archived = newArchived;
            this.updatedAt = now;
        }
    }

    public UUID getId() {
        return id;
    }

    public UUID getUserId() {
        return userId;
    }

    public String getName() {
        return name;
    }

    public String getSystemKey() {
        return systemKey;
    }

    public CategoryKind getKind() {
        return kind;
    }

    public boolean isSystemCategory() {
        return systemCategory;
    }

    public boolean isArchived() {
        return archived;
    }

    public Long getVersion() {
        return version;
    }
}
