package com.moneylog.infra.mail;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.gateways.EmailSender;

public class FakeEmailSender implements EmailSender {

    private final List<EmailMessage> sent = new CopyOnWriteArrayList<>();
    private final AtomicBoolean failing = new AtomicBoolean();

    @Override
    public EmailDelivery send(EmailMessage message) {
        if (failing.get()) {
            throw new EmailDeliveryException("Falha simulada no envio de e-mail.");
        }
        sent.add(message);
        return new EmailDelivery("fake-" + UUID.randomUUID());
    }

    public List<EmailMessage> sent() {
        return List.copyOf(sent);
    }

    public EmailMessage last() {
        return sent.isEmpty() ? null : sent.get(sent.size() - 1);
    }

    public void failNextSends(boolean fail) {
        failing.set(fail);
    }

    public void clear() {
        sent.clear();
        failing.set(false);
    }
}
