package com.moneylog.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;
import org.slf4j.LoggerFactory;

import software.amazon.awssdk.awscore.exception.AwsErrorDetails;
import software.amazon.awssdk.awscore.exception.AwsServiceException;
import software.amazon.awssdk.core.exception.SdkClientException;
import software.amazon.awssdk.services.sesv2.SesV2Client;
import software.amazon.awssdk.services.sesv2.model.SendEmailRequest;
import software.amazon.awssdk.services.sesv2.model.SendEmailResponse;

import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.mail.app.EmailMessage;

@DisplayName("Adaptadores de e-mail")
class EmailSendersTest {

    private static final String TOKEN = "AbCdEfGhIjKlMnOpQrStUvWxYz0123456789-_ABCDE";
    private static final String LINK = "https://moneylog.mviana.tech/convite#" + TOKEN;

    private static EmailMessage message(String replyTo) {
        return new EmailMessage("ana.silva@example.com", "Convite", "<p>html " + LINK + "</p>", "texto\n" + LINK + "\n", replyTo);
    }

    @Test
    @DisplayName("SES aceita: leva From, Reply-To, configuration set, texto e HTML em UTF-8 e devolve o messageId")
    void deveEnviarPeloSes() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("ses-123").build());
        SesEmailSender sender = new SesEmailSender(client, "convite@moneylog.mviana.tech", "c@mviana.tech", "moneylog-prod");

        EmailDelivery delivery = sender.send(message(null));

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(captor.capture());
        SendEmailRequest request = captor.getValue();
        assertThat(delivery.messageId()).isEqualTo("ses-123");
        assertThat(request.fromEmailAddress()).isEqualTo("convite@moneylog.mviana.tech");
        assertThat(request.destination().toAddresses()).containsExactly("ana.silva@example.com");
        assertThat(request.replyToAddresses()).containsExactly("c@mviana.tech");
        assertThat(request.configurationSetName()).isEqualTo("moneylog-prod");
        assertThat(request.content().simple().subject().data()).isEqualTo("Convite");
        assertThat(request.content().simple().body().html().data()).contains("<p>html");
        assertThat(request.content().simple().body().text().data()).startsWith("texto");
        assertThat(request.content().simple().body().html().charset()).isEqualTo("UTF-8");
    }

    @Test
    @DisplayName("o Reply-To da mensagem tem precedência e, sem configuration set, o campo não vai")
    void deveUsarReplyToDaMensagem() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenReturn(SendEmailResponse.builder().messageId("m").build());

        new SesEmailSender(client, "convite@moneylog.mviana.tech", "c@mviana.tech", "").send(message("outro@mviana.tech"));

        ArgumentCaptor<SendEmailRequest> captor = ArgumentCaptor.forClass(SendEmailRequest.class);
        verify(client).sendEmail(captor.capture());
        assertThat(captor.getValue().replyToAddresses()).containsExactly("outro@mviana.tech");
        assertThat(captor.getValue().configurationSetName()).isNull();
    }

    @Test
    @DisplayName("erro do SES vira EmailDeliveryException sem vazar credencial, corpo ou link")
    void deveTraduzirFalhaDoSes() {
        ListAppender<ILoggingEvent> logs = capture(SesEmailSender.class);
        SesV2Client client = mock(SesV2Client.class);
        AwsServiceException aws = AwsServiceException.builder().statusCode(400)
                .message("segredo-AKIAFAKE " + TOKEN)
                .awsErrorDetails(AwsErrorDetails.builder().errorCode("MessageRejected").errorMessage("segredo-AKIAFAKE " + TOKEN).build()).build();
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(aws);
        SesEmailSender sender = new SesEmailSender(client, "convite@moneylog.mviana.tech", "c@mviana.tech", "cs");

        assertThatThrownBy(() -> sender.send(message(null)))
                .isInstanceOf(EmailDeliveryException.class)
                .hasMessageNotContaining("AKIA").hasMessageNotContaining(TOKEN).hasNoCause()
                .extracting("code").isEqualTo("EMAIL_DELIVERY_FAILED");
        String all = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(all).contains("MessageRejected").doesNotContain("AKIA").doesNotContain(TOKEN).doesNotContain("ana.silva").contains("a***@example.com");
        assertThat(logs.list).allSatisfy(e -> assertThat(e.getThrowableProxy()).isNull());
    }

    @Test
    @DisplayName("falha de rede do cliente também vira EmailDeliveryException")
    void deveTraduzirFalhaDeRede() {
        SesV2Client client = mock(SesV2Client.class);
        when(client.sendEmail(any(SendEmailRequest.class))).thenThrow(SdkClientException.create("timeout"));

        assertThatThrownBy(() -> new SesEmailSender(client, "f@x.com", "", "").send(message(null))).isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    @DisplayName("log mascara o destinatário e nunca imprime o corpo; o link só aparece com a opção de dev")
    void deveLogarMascaradoSemCorpo() {
        ListAppender<ILoggingEvent> logs = capture(LogEmailSender.class);

        new LogEmailSender(false).send(message(null));
        String prod = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(prod).contains("a***@example.com").doesNotContain("ana.silva").doesNotContain(TOKEN).doesNotContain("html").doesNotContain("texto");

        logs.list.clear();
        new LogEmailSender(true).send(message(null));
        String dev = logs.list.stream().map(ILoggingEvent::getFormattedMessage).reduce("", String::concat);
        assertThat(dev).contains(LINK).doesNotContain("ana.silva").doesNotContain("<p>");
        assertThat(logs.list.stream().filter(e -> e.getFormattedMessage().contains(TOKEN)).count()).isEqualTo(1);
    }

    @Test
    @DisplayName("none recusa o envio com erro de domínio, sem fingir que entregou")
    void deveRecusarComEmailDesligado() {
        assertThatThrownBy(() -> new DisabledEmailSender().send(message(null))).isInstanceOf(EmailDeliveryException.class);
    }

    @Test
    @DisplayName("fake guarda as mensagens e pode simular falha")
    void deveGuardarEsimularFalha() {
        FakeEmailSender fake = new FakeEmailSender();
        EmailMessage msg = message(null);

        assertThat(fake.send(msg).messageId()).startsWith("fake-");
        assertThat(fake.sent()).containsExactly(msg);
        assertThat(fake.last()).isEqualTo(msg);

        fake.failNextSends(true);
        assertThatThrownBy(() -> fake.send(msg)).isInstanceOf(EmailDeliveryException.class);
        assertThat(fake.sent()).hasSize(1);

        fake.clear();
        assertThat(fake.sent()).isEmpty();
        assertThat(fake.last()).isNull();
        assertThat(fake.send(msg)).isNotNull();
    }

    @Test
    @DisplayName("mensagem recusa destinatário múltiplo, assunto com quebra de linha e campos vazios")
    void deveValidarMensagem() {
        assertThatThrownBy(() -> new EmailMessage("a@x.com,b@x.com", "s", "h", "t", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailMessage("a@x.com", "s\nBcc: b@x.com", "h", "t", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailMessage("a@x.com", "s", " ", "t", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailMessage("a@b@c", "s", "h", "t", null)).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailMessage("a@x.com", "s", "h", "t", "a@x.com, b@x.com")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailMessage("a@x.com", "s", "h", "t", "a@b@c")).isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new EmailMessage("a@x.com", "s", "h", "t", "a@x.com\nBcc: b@x.com")).isInstanceOf(IllegalArgumentException.class);
        assertThat(message(null).toString()).doesNotContain("ana.silva").doesNotContain(TOKEN);
    }

    private static ListAppender<ILoggingEvent> capture(Class<?> type) {
        Logger logger = (Logger) LoggerFactory.getLogger(type);
        ListAppender<ILoggingEvent> appender = new ListAppender<>();
        appender.start();
        logger.addAppender(appender);
        return appender;
    }
}
