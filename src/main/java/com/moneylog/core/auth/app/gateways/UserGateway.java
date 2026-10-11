package com.moneylog.core.auth.app.gateways;

import java.util.Optional;
import java.util.UUID;

import java.time.Instant;

import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.User;

public interface UserGateway {

    Optional<User> findOwner();

    boolean existsById(UUID id);

    Optional<User> findByEmail(String email);

    void insert(User user);

    void updateEmail(UUID id, String email);

    Optional<OwnerAccess> findAccess(UUID id);

    void resetPassword(UUID id, Instant now);

    void storeSetupCode(UUID id, String codeHash, Instant expiresAt, Instant now);

    boolean completeSetup(UUID id, String expectedCodeHash, String passwordHash, Instant now);
}
