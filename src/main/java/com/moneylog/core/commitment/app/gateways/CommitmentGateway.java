package com.moneylog.core.commitment.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

/** Porta de persistência dos compromissos acompanhados. Todo método filtra pelo {@code userId}. */
public interface CommitmentGateway {

    Versioned<TrackedCommitment> insert(TrackedCommitment commitment);

    /** @throws com.moneylog.core.sk.exception.VersionConflictException se a versão gravada é outra */
    Versioned<TrackedCommitment> update(TrackedCommitment commitment, long expectedVersion);

    /** Trava a linha ({@code SELECT ... FOR UPDATE}). */
    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<TrackedCommitment>> findById(UUID userId, UUID id);

    /** Página em {@code name asc, id asc}. */
    Slice<Versioned<TrackedCommitment>> list(UUID userId, PageQuery page);

    /** Todos (para a visão agregada de dívidas). */
    List<TrackedCommitment> findAll(UUID userId);
}
