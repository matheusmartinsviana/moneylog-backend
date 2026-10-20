package com.moneylog.core.mail.app;

public interface EmailTemplate {

    RenderedEmail renderInvitation(InvitationEmailModel model);
}
