package com.moneylog.core.commitment.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Versioned;

public interface CommitmentGateway {

    Versioned<TrackedCommitment> insert(TrackedCommitment commitment);

    Versioned<TrackedCommitment> update(TrackedCommitment commitment, long expectedVersion);

    void lockForUpdate(UUID userId, UUID id);

    Optional<Versioned<TrackedCommitment>> findById(UUID userId, UUID id);

    Slice<Versioned<TrackedCommitment>> list(UUID userId, PageQuery page);

    List<TrackedCommitment> findAll(UUID userId);
}
