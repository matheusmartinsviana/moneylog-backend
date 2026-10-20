package com.moneylog.core.invitation.app;

import java.time.Instant;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.EmailAlreadyRegisteredException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotFoundException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationNotResendableException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationPendingException;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationsDisabledException;
import com.moneylog.core.invitation.exception.InvitationExceptions.UserLimitReachedException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.TimeProvider;

public class InvitationIssuerImpl implements InvitationIssuer {

    private final InvitationGateway invitations;
    private final UserGateway users;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final InvitationPolicy policy;

    public InvitationIssuerImpl(InvitationGateway invitations, UserGateway users, IdGenerator ids, TimeProvider time,
                                InvitationPolicy policy) {
        this.invitations = invitations;
        this.users = users;
        this.ids = ids;
        this.time = time;
        this.policy = policy;
    }

    @Override
    public IssuedInvitation issue(UUID inviterId, String rawEmail) {
        requireEnabled();
        String email = User.normalize(rawEmail);
        String inviterEmail = inviterEmail(inviterId);
        Instant now = time.now();
        invitations.lockIssuance();
        invitations.expireOverdueOf(email, now);
        ensureEmailAvailable(email, null);
        ensureRoomForOne(now);
        String token = SecureToken.generate();
        Invitation invitation = Invitation.issue(ids.next(), email, SecureToken.hash(token), inviterId, now, policy.expiresAt(now));
        invitations.insert(invitation);
        return new IssuedInvitation(invitation, inviterEmail, token);
    }

    @Override
    public IssuedInvitation reissue(UUID inviterId, UUID invitationId) {
        requireEnabled();
        String inviterEmail = inviterEmail(inviterId);
        Instant now = time.now();
        invitations.lockIssuance();
        Invitation current = invitations.findByIdForUpdate(invitationId).orElseThrow(InvitationNotFoundException::new);
        if (!current.isResendableAt(now)) {
            throw new InvitationNotResendableException();
        }
        invitations.expireOverdueOf(current.email(), now);
        if (!current.isPendingAt(now)) {
            ensureEmailAvailable(current.email(), current.id());
            ensureRoomForOne(now);
        }
        String token = SecureToken.generate();
        Invitation reissued = current.reissue(SecureToken.hash(token), now, policy.expiresAt(now));
        invitations.update(reissued);
        return new IssuedInvitation(reissued, inviterEmail, token);
    }

    @Override
    public Invitation recordDelivery(UUID invitationId, String tokenHash, boolean delivered, Instant at) {
        Invitation current = invitations.findByIdForUpdate(invitationId).orElseThrow(InvitationNotFoundException::new);
        if (!current.tokenHash().equals(tokenHash)) {
            return current;
        }
        Invitation updated = delivered ? current.delivered(at) : current.deliveryFailed();
        invitations.update(updated);
        return updated;
    }

    private void requireEnabled() {
        if (!policy.enabled()) {
            throw new InvitationsDisabledException();
        }
    }

    private String inviterEmail(UUID inviterId) {
        return users.findById(inviterId).map(User::email).orElseThrow(() -> new IllegalStateException("o usuário que convida não existe"));
    }

    private void ensureEmailAvailable(String email, UUID ownInvitationId) {
        if (users.existsByEmail(email)) {
            throw new EmailAlreadyRegisteredException();
        }
        Optional<Invitation> pending = invitations.findPendingByEmail(email);
        if (pending.isPresent() && !pending.get().id().equals(ownInvitationId)) {
            throw new InvitationPendingException(pending.get().id());
        }
    }

    private void ensureRoomForOne(Instant now) {
        if (users.count() + invitations.countPendingNotExpired(now) >= policy.maxUsers()) {
            throw new UserLimitReachedException();
        }
    }
}
