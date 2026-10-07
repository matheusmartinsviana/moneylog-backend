package com.moneylog.core.auth.app.gateways;

import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.domain.User;

public interface UserGateway {

    /** A única linha de {@code users} (o dono), se existir. */
    Optional<User> findOwner();

    /** O usuário existe (o dono de um token válido pode ter sido removido: B6). */
    boolean existsById(UUID id);

    Optional<User> findByEmail(String email);

    void insert(User user);

    /** Troca o e-mail mantendo o id (S-82). */
    void updateEmail(UUID id, String email);
}
