package com.moneylog.core.invitation.app.usecase;

import java.time.Instant;

import com.moneylog.core.auth.app.AuthSession;
import com.moneylog.core.auth.app.SessionOpener;
import com.moneylog.core.auth.app.UserCreated;
import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserCreatedPublisher;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.PasswordPolicy;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.invitation.app.InvitationPolicy;
import com.moneylog.core.invitation.app.gateways.InvitationGateway;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvalidInvitationException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.TimeProvider;

public class AcceptInvitationUseCaseImpl implements AcceptInvitationUseCase {

    private final InvitationGateway invitations;
    private final UserGateway users;
    private final PasswordVerifier passwordVerifier;
    private final SessionOpener sessions;
    private final UserCreatedPublisher userCreated;
    private final IdGenerator ids;
    private final TimeProvider time;
    private final InvitationPolicy policy;

    public AcceptInvitationUseCaseImpl(InvitationGateway invitations, UserGateway users, PasswordVerifier passwordVerifier,
                                       SessionOpener sessions, UserCreatedPublisher userCreated, IdGenerator ids, TimeProvider time,
                                       InvitationPolicy policy) {
        this.invitations = invitations;
        this.users = users;
        this.passwordVerifier = passwordVerifier;
        this.sessions = sessions;
        this.userCreated = userCreated;
        this.ids = ids;
        this.time = time;
        this.policy = policy;
    }

    @Override
    public AuthSession handle(AcceptInvitationCommand command) {
        if (!policy.enabled() || command.token() == null) {
            throw new InvalidInvitationException();
        }
        Instant now = time.now();
        String tokenHash = SecureToken.hash(command.token());
        Invitation candidate = invitations.findByTokenHash(tokenHash).filter(found -> found.isPendingAt(now))
                .orElseThrow(InvalidInvitationException::new);
        if (users.existsByEmail(candidate.email())) {
            invitations.revokeInIndependentTransaction(candidate.id());
            throw new InvalidInvitationException();
        }
        PasswordPolicy.check(command.password(), candidate.email());
        String passwordHash = passwordVerifier.encode(command.password());

        Invitation locked = invitations.findByTokenHashForUpdate(tokenHash).filter(found -> found.isPendingAt(now))
                .orElseThrow(InvalidInvitationException::new);
        User user = User.member(ids.next(), locked.email());
        users.insertWithPassword(user, passwordHash, now);
        if (!invitations.markAccepted(locked.accept(user.id(), now))) {
            throw new InvalidInvitationException();
        }
        userCreated.publish(new UserCreated(user.id(), user.email()));
        users.markLogin(user.id(), now);
        return sessions.open(user, ids.next(), now);
    }
}
