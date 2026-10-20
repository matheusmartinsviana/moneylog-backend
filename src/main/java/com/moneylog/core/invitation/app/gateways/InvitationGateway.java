package com.moneylog.core.invitation.app.gateways;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.invitation.domain.Invitation;

public interface InvitationGateway {

    void lockIssuance();

    Optional<Invitation> findByIdForUpdate(UUID id);

    Optional<Invitation> findByTokenHash(String tokenHash);

    Optional<Invitation> findByTokenHashForUpdate(String tokenHash);

    Optional<Invitation> findPendingByEmail(String email);

    List<Invitation> findAllNewestFirst();

    long countPendingNotExpired(Instant now);

    int expireOverdueOf(String email, Instant now);

    int expireOverdue(Instant now);

    void insert(Invitation invitation);

    void update(Invitation invitation);

    boolean markAccepted(Invitation accepted);

    void revokeInIndependentTransaction(UUID id);

    int deleteFinalBefore(Instant cutoff);
}
