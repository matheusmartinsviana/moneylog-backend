package com.moneylog.infra.auth.gateways;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.OwnerAccess;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.auth.domain.UserRole;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.auth.persistence.UserEntity;
import com.moneylog.infra.auth.persistence.UserRepositoryJpa;

public class UserGatewayImpl implements UserGateway {

    private final UserRepositoryJpa repository;
    private final TimeProvider time;

    public UserGatewayImpl(UserRepositoryJpa repository, TimeProvider time) {
        this.repository = repository;
        this.time = time;
    }

    @Override
    public Optional<User> findByRole(UserRole role) {
        return repository.findFirstByRole(role).map(UserGatewayImpl::toDomain);
    }

    @Override
    public Optional<User> findById(UUID id) {
        return repository.findById(id).map(UserGatewayImpl::toDomain);
    }

    @Override
    public Optional<User> findByIdLockingShared(UUID id) {
        return repository.findByIdLockingShared(id).map(UserGatewayImpl::toDomain);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email).map(UserGatewayImpl::toDomain);
    }

    @Override
    public void insert(User user) {
        repository.saveAndFlush(new UserEntity(user.id(), user.email(), user.role(), user.status(), time.zone().getId(), time.now()));
    }

    @Override
    public void updateRole(UUID id, UserRole role, Instant now) {
        UserEntity entity = repository.findById(id).orElseThrow();
        entity.changeRole(role, now);
        repository.saveAndFlush(entity);
    }

    @Override
    public void markLogin(UUID id, Instant now) {
        repository.markLogin(id, now);
    }

    @Override
    public Optional<OwnerAccess> findAccess(UUID id) {
        return repository.findById(id).map(e -> new OwnerAccess(e.getPasswordHash(), e.isPasswordSetupPending(),
                e.getSetupCodeHash(), e.getSetupCodeExpiresAt()));
    }

    @Override
    public void resetPassword(UUID id, Instant now) {
        UserEntity entity = repository.findById(id).orElseThrow();
        entity.resetPassword(now);
        repository.saveAndFlush(entity);
    }

    @Override
    public void storeSetupCode(UUID id, String codeHash, Instant expiresAt, Instant now) {
        UserEntity entity = repository.findById(id).orElseThrow();
        entity.storeSetupCode(codeHash, expiresAt, now);
        repository.saveAndFlush(entity);
    }

    @Override
    public boolean completeSetup(UUID id, String expectedCodeHash, String passwordHash, Instant now) {
        return repository.completeSetup(id, expectedCodeHash, passwordHash, now) == 1;
    }

    private static User toDomain(UserEntity entity) {
        return new User(entity.getId(), entity.getEmail(), entity.getRole(), entity.getStatus());
    }
}
