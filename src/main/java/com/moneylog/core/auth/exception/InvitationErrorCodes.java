package com.moneylog.core.auth.exception;

public final class InvitationErrorCodes {

    public static final String CANNOT_DISABLE_OWNER = "CANNOT_DISABLE_OWNER";
    public static final String EMAIL_ALREADY_REGISTERED = "EMAIL_ALREADY_REGISTERED";
    public static final String INVALID_INVITATION = "INVALID_INVITATION";
    public static final String INVITATIONS_DISABLED = "INVITATIONS_DISABLED";
    public static final String INVITATION_NOT_FOUND = "INVITATION_NOT_FOUND";
    public static final String INVITATION_NOT_RESENDABLE = "INVITATION_NOT_RESENDABLE";
    public static final String INVITATION_NOT_REVOCABLE = "INVITATION_NOT_REVOCABLE";
    public static final String INVITATION_PENDING = "INVITATION_PENDING";
    public static final String USER_LIMIT_REACHED = "USER_LIMIT_REACHED";
    public static final String USER_NOT_FOUND = "USER_NOT_FOUND";

    private InvitationErrorCodes() {
    }
}
