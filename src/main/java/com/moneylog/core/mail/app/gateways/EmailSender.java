package com.moneylog.core.mail.app.gateways;

import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailMessage;

public interface EmailSender {

    EmailDelivery send(EmailMessage message);
}
