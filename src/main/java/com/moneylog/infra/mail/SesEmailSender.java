package com.moneylog.infra.mail;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.Body;
import software.amazon.awssdk.services.sesv2.model.Content;
import software.amazon.awssdk.services.sesv2.model.Destination;
import software.amazon.awssdk.services.sesv2.model.EmailContent;
import software.amazon.awssdk.services.sesv2.model.Message;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import com.moneylog.core.mail.app.EmailAddressMask;
import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.gateways.EmailSender;

public class SesEmailSender implements EmailSender, AutoCloseable {

    private static final Logger log = LoggerFactory.getLogger(SesEmailSender.class);
    private static final String CHARSET = "UTF-8";

    private final SesV2Client client;
    private final String from;
    private final String defaultReplyTo;
    private final String configurationSet;

    public SesEmailSender(SesV2Client client, String from, String defaultReplyTo, String configurationSet) {
        this.client = client;
        this.from = from;
        this.defaultReplyTo = defaultReplyTo == null ? "" : defaultReplyTo;
        this.configurationSet = configurationSet == null ? "" : configurationSet;
    }

    @Override
    public EmailDelivery send(EmailMessage message) {
        try {
            SendEmailResponse response = client.sendEmail(request(message));
            return new EmailDelivery(response.messageId());
        } catch (SdkException e) {
            log.warn("Falha ao enviar e-mail pelo SES: para={}, tipo={}, status={}, codigo={}", EmailAddressMask.mask(message.to()),
                    e.getClass().getSimpleName(), statusOf(e), codeOf(e));
            throw new EmailDeliveryException("Não foi possível enviar o e-mail.");
        }
    }

    @Override
    public void close() {
        client.close();
    }

    private SendEmailRequest request(EmailMessage message) {
        SendEmailRequest.Builder request = SendEmailRequest.builder()
                .fromEmailAddress(from)
                .destination(Destination.builder().toAddresses(message.to()).build())
                .content(EmailContent.builder().simple(Message.builder()
                        .subject(content(message.subject()))
                        .body(Body.builder().html(content(message.html())).text(content(message.text())).build())
                        .build()).build());
        String replyTo = message.replyTo() != null ? message.replyTo() : defaultReplyTo;
        if (!replyTo.isEmpty()) {
            request.replyToAddresses(replyTo);
        }
        if (!configurationSet.isEmpty()) {
            request.configurationSetName(configurationSet);
        }
        return request.build();
    }

    private static Content content(String data) {
        return Content.builder().data(data).charset(CHARSET).build();
    }

    private static String statusOf(SdkException e) {
        return e instanceof AwsServiceException aws ? String.valueOf(aws.statusCode()) : "-";
    }

    private static String codeOf(SdkException e) {
        if (e instanceof AwsServiceException aws && aws.awsErrorDetails() != null && aws.awsErrorDetails().errorCode() != null) {
            return aws.awsErrorDetails().errorCode();
        }
        return "-";
    }
}
