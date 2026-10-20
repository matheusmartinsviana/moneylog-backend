package com.moneylog.infra.mail;

import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.gateways.EmailSender;

public class DisabledEmailSender implements EmailSender {

    @Override
    public EmailDelivery send(EmailMessage message) {
        throw new EmailDeliveryException("O envio de e-mail está desligado neste ambiente (MAIL_PROVIDER=none).");
    }
}
