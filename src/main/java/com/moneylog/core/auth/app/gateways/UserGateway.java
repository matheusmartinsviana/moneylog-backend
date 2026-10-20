package com.moneylog.core.auth.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import java.time.Instant;

import com.moneylog.core.auth.domain.ManagedUser;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.auth.domain.UserStatus;

public interface UserGateway {

    Optional<User> findByRole(UserRole role);

    Optional<User> findById(UUID id);

    Optional<User> findByIdLockingShared(UUID id);

    Optional<User> findByEmail(String email);

    void insert(User user);


    void updateRole(UUID id, UserRole role, Instant now);

    List<ManagedUser> listManaged();

    Optional<ManagedUser> findManagedById(UUID id);

    void updateStatus(UUID id, UserStatus status, Instant now);

    void markLogin(UUID id, Instant now);

    Optional<OwnerAccess> findAccess(UUID id);

    void resetPassword(UUID id, Instant now);

    void storeSetupCode(UUID id, String codeHash, Instant expiresAt, Instant now);

    boolean completeSetup(UUID id, String expectedCodeHash, String passwordHash, Instant now);
}
