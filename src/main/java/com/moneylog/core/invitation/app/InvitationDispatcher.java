package com.moneylog.core.invitation.app;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationDeliveryFailedException;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.EmailTemplate;
import com.moneylog.core.mail.app.InvitationEmailModel;
import com.moneylog.core.mail.app.RenderedEmail;
import com.moneylog.core.mail.app.gateways.EmailSender;
import com.moneylog.core.sk.time.TimeProvider;

public class InvitationDispatcher {

    private static final Logger log = LoggerFactory.getLogger(InvitationDispatcher.class);

    private final InvitationIssuer issuer;
    private final EmailSender sender;
    private final EmailTemplate template;
    private final TimeProvider time;
    private final InvitationPolicy policy;

    public InvitationDispatcher(InvitationIssuer issuer, EmailSender sender, EmailTemplate template, TimeProvider time,
                                InvitationPolicy policy) {
        this.issuer = issuer;
        this.sender = sender;
        this.template = template;
        this.time = time;
        this.policy = policy;
    }

    public InvitationView deliver(IssuedInvitation issued) {
        Invitation invitation = issued.invitation();
        UUID id = invitation.id();
        boolean delivered = send(issued);
        Instant now = time.now();
        Invitation recorded = issuer.recordDelivery(id, invitation.tokenHash(), delivered, now);
        if (!delivered) {
            throw new InvitationDeliveryFailedException(id);
        }
        return InvitationView.of(recorded, now);
    }

    private boolean send(IssuedInvitation issued) {
        Invitation invitation = issued.invitation();
        try {
            InvitationEmailModel model = new InvitationEmailModel(issued.inviterEmail(), invitation.email(), issued.token(),
                    invitation.expiresAt(), policy.validityDays(), issued.inviterName());
            RenderedEmail rendered = template.renderInvitation(model);
            sender.send(new EmailMessage(invitation.email(), rendered.subject(), rendered.html(), rendered.text(), null));
            return true;
        } catch (RuntimeException e) {
            log.warn("Falha ao enviar o e-mail do convite {}: {}", invitation.id(), e.getClass().getSimpleName());
            return false;
        }
    }
}
