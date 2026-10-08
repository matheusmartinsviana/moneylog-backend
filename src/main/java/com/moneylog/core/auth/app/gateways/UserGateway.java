package com.moneylog.core.auth.app.gateways;

import java.util.Optional;
import java.util.UUID;

import java.time.Instant;

import com.moneylog.core.auth.domain.OwnerAccess;
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

    /** Estado de senha e de código de primeiro acesso do usuário (ADR-015). */
    Optional<OwnerAccess> findAccess(UUID id);

    /**
     * Redefinição pedida pelo dono: apaga a senha do banco e o código, marca o setup como pendente. Mantém o id e
     * todos os dados de negócio.
     */
    void resetPassword(UUID id, Instant now);

    /** Grava o hash do código de primeiro acesso (substitui o anterior) e a validade. */
    void storeSetupCode(UUID id, String codeHash, Instant expiresAt, Instant now);

    /**
     * Define a senha de forma atômica e de uso único: só se ainda não há senha e o hash do código gravado é igual a
     * {@code expectedCodeHash}. Apaga o código. Devolve {@code false} se perdeu a corrida (já usado).
     */
    boolean completeSetup(UUID id, String expectedCodeHash, String passwordHash, Instant now);
}
