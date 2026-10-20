package com.moneylog.infra.mail;

import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import com.moneylog.core.mail.app.EmailAddressMask;
import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.gateways.EmailSender;

public class LogEmailSender implements EmailSender {

    private static final Logger log = LoggerFactory.getLogger(LogEmailSender.class);
    private static final Pattern INVITATION_LINK = Pattern.compile("https?://\\S+/convite#[A-Za-z0-9_-]+");

    private final boolean showInvitationLink;

    public LogEmailSender(boolean showInvitationLink) {
        this.showInvitationLink = showInvitationLink;
    }

    @Override
    public EmailDelivery send(EmailMessage message) {
        String messageId = "log-" + UUID.randomUUID();
        log.info("E-mail simulado (MAIL_PROVIDER=log): id={}, para={}, assunto={}", messageId, EmailAddressMask.mask(message.to()), message.subject());
        if (showInvitationLink) {
            Matcher link = INVITATION_LINK.matcher(message.text());
            if (link.find()) {
                log.info("Link do convite (somente dev, aparece uma vez): {}", link.group());
            }
        }
        return new EmailDelivery(messageId);
    }
}
