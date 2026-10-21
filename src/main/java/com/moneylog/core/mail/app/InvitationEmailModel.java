package com.moneylog.core.mail.app;

import java.time.Instant;
import java.util.Objects;
import java.util.regex.Pattern;

public record InvitationEmailModel(String inviterEmail, String inviteeEmail, String token, Instant expiresAt, int validityDays,
                              String inviterName) {

    private static final Pattern URL_SAFE_TOKEN = Pattern.compile("[A-Za-z0-9_-]{16,128}");

    public InvitationEmailModel(String inviterEmail, String inviteeEmail, String token, Instant expiresAt, int validityDays) {
        this(inviterEmail, inviteeEmail, token, expiresAt, validityDays, null);
    }

    public InvitationEmailModel {
        inviterName = InviterName.clean(inviterName);
        Objects.requireNonNull(inviterEmail, "inviterEmail");
        Objects.requireNonNull(inviteeEmail, "inviteeEmail");
        Objects.requireNonNull(token, "token");
        Objects.requireNonNull(expiresAt, "expiresAt");
        if (!URL_SAFE_TOKEN.matcher(token).matches()) {
            throw new IllegalArgumentException("token do convite deve ser base64url");
        }
        if (validityDays < 1) {
            throw new IllegalArgumentException("validityDays deve ser positivo");
        }
    }

    @Override
    public String toString() {
        return "InvitationEmailModel[invitee=" + EmailAddressMask.mask(inviteeEmail) + "]";
    }
}
