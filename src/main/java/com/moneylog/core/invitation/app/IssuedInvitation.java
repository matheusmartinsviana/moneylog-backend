package com.moneylog.core.invitation.app;

import java.util.Objects;

import com.moneylog.core.invitation.domain.Invitation;

public record IssuedInvitation(Invitation invitation, String inviterEmail, String inviterName, String token) {

    public IssuedInvitation(Invitation invitation, String inviterEmail, String token) {
        this(invitation, inviterEmail, null, token);
    }

    public IssuedInvitation {
        Objects.requireNonNull(invitation, "invitation");
        Objects.requireNonNull(inviterEmail, "inviterEmail");
        Objects.requireNonNull(token, "token");
    }

    @Override
    public String toString() {
        return "IssuedInvitation[invitation=" + invitation + "]";
    }
}
