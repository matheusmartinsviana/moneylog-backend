package com.moneylog.infra.auth.gateways;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.PageRequest;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
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
    public Optional<User> findOwner() {
        return repository.findOldest(PageRequest.of(0, 1)).stream().findFirst().map(UserGatewayImpl::toDomain);
    }

    @Override
    public boolean existsById(UUID id) {
        return repository.existsById(id);
    }

    @Override
    public Optional<User> findByEmail(String email) {
        return repository.findByEmail(email).map(UserGatewayImpl::toDomain);
    }

    @Override
    public void insert(User user) {
        repository.saveAndFlush(new UserEntity(user.id(), user.email(), time.zone().getId(), time.now()));
    }

    @Override
    public void updateEmail(UUID id, String email) {
        UserEntity entity = repository.findById(id).orElseThrow();
        entity.changeEmail(email, time.now());
        repository.saveAndFlush(entity);
    }

    private static User toDomain(UserEntity entity) {
        return new User(entity.getId(), entity.getEmail());
    }
}
