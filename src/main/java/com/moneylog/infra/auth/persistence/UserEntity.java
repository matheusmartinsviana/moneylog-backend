package com.moneylog.infra.auth.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

@Entity
@Table(name = "users")
public class UserEntity {

    @Id
    private UUID id;

    @Column(nullable = false, length = 254)
    private String email;

    @Column(name = "time_zone", nullable = false, length = 64)
    private String timeZone;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @Version
    private Long version;

    @Column(name = "password_hash", length = 100)
    private String passwordHash;

    @Column(name = "password_set_at")
    private Instant passwordSetAt;

    @Column(name = "password_setup_pending", nullable = false)
    private boolean passwordSetupPending;

    @Column(name = "setup_code_hash", length = 64)
    private String setupCodeHash;

    @Column(name = "setup_code_expires_at")
    private Instant setupCodeExpiresAt;

    protected UserEntity() {
    }

    public UserEntity(UUID id, String email, String timeZone, Instant now) {
        this.id = id;
        this.email = email;
        this.timeZone = timeZone;
        this.createdAt = now;
        this.updatedAt = now;
    }

    public UUID getId() {
        return id;
    }

    public String getEmail() {
        return email;
    }

    public void changeEmail(String newEmail, Instant now) {
        this.email = newEmail;
        this.updatedAt = now;
    }

    public String getPasswordHash() {
        return passwordHash;
    }

    public boolean isPasswordSetupPending() {
        return passwordSetupPending;
    }

    public String getSetupCodeHash() {
        return setupCodeHash;
    }

    public Instant getSetupCodeExpiresAt() {
        return setupCodeExpiresAt;
    }

    public void resetPassword(Instant now) {
        this.passwordHash = null;
        this.passwordSetAt = null;
        this.passwordSetupPending = true;
        this.setupCodeHash = null;
        this.setupCodeExpiresAt = null;
        this.updatedAt = now;
    }

    public void storeSetupCode(String codeHash, Instant expiresAt, Instant now) {
        this.setupCodeHash = codeHash;
        this.setupCodeExpiresAt = expiresAt;
        this.updatedAt = now;
    }
}
