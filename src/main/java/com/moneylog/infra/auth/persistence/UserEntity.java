package com.moneylog.infra.auth.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.domain.UserStatus;

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

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserRole role;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 16)
    private UserStatus status;

    @Column(name = "last_login_at")
    private Instant lastLoginAt;

    @Column(name = "disabled_at")
    private Instant disabledAt;

    protected UserEntity() {
    }

    public UserEntity(UUID id, String email, UserRole role, UserStatus status, String timeZone, Instant now) {
        this.id = id;
        this.email = email;
        this.role = role;
        this.status = status;
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

    public UserRole getRole() {
        return role;
    }

    public UserStatus getStatus() {
        return status;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getLastLoginAt() {
        return lastLoginAt;
    }

    public void changeRole(UserRole newRole, Instant now) {
        this.role = newRole;
        this.updatedAt = now;
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

    public void definePassword(String hash, Instant now) {
        this.passwordHash = hash;
        this.passwordSetAt = now;
        this.passwordSetupPending = false;
        this.updatedAt = now;
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
