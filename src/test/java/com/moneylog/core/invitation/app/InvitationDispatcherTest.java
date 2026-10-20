package com.moneylog.core.invitation.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.inOrder;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.mockito.ArgumentCaptor;
import org.mockito.InOrder;
import org.mockito.Mock;
import org.mockito.junit.jupiter.MockitoExtension;

import com.moneylog.core.invitation.domain.DeliveryStatus;
import com.moneylog.core.invitation.domain.Invitation;
import com.moneylog.core.invitation.exception.InvitationExceptions.InvitationDeliveryFailedException;
import com.moneylog.core.mail.app.EmailDelivery;
import com.moneylog.core.mail.app.EmailDeliveryException;
import com.moneylog.core.mail.app.EmailMessage;
import com.moneylog.core.mail.app.EmailTemplate;
import com.moneylog.core.mail.app.InvitationEmailModel;
import com.moneylog.core.mail.app.RenderedEmail;
import com.moneylog.core.mail.app.gateways.EmailSender;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.core.sk.time.ClockTimeProvider;

@ExtendWith(MockitoExtension.class)
@DisplayName("InvitationDispatcher")
class InvitationDispatcherTest {

    private static final Instant NOW = Instant.parse("2026-10-19T12:00:00Z");
    private static final UUID OWNER_ID = UUID.fromString("00000000-0000-0000-0000-000000000001");

    @Mock
    private InvitationIssuer issuer;
    @Mock
    private EmailSender sender;
    @Mock
    private EmailTemplate template;

    private InvitationDispatcher dispatcher;
    private IssuedInvitation issued;

    @BeforeEach
    void setUp() {
        InvitationPolicy policy = new InvitationPolicy(true, 7, 10);
        dispatcher = new InvitationDispatcher(issuer, sender, template, new ClockTimeProvider(Clock.fixed(NOW, ZoneOffset.UTC)), policy);
        String token = SecureToken.generate();
        Invitation invitation = Invitation.issue(UUID.randomUUID(), "ana@example.com", SecureToken.hash(token), OWNER_ID, NOW,
                policy.expiresAt(NOW));
        issued = new IssuedInvitation(invitation, "dono@example.com", token);
    }

    @Test
    @DisplayName("envia depois de gravar, deriva validityDays e expiresAt do mesmo valor e registra SENT")
    void sendsAndRecords() {
        when(template.renderInvitation(any())).thenReturn(new RenderedEmail("assunto", "<p>oi</p>", "oi"));
        when(sender.send(any())).thenReturn(new EmailDelivery("id-1"));
        when(issuer.recordDelivery(eq(issued.invitation().id()), eq(issued.invitation().tokenHash()), eq(true), eq(NOW))).thenReturn(issued.invitation().delivered(NOW));

        InvitationView view = dispatcher.deliver(issued);

        ArgumentCaptor<InvitationEmailModel> model = ArgumentCaptor.forClass(InvitationEmailModel.class);
        InOrder order = inOrder(template, sender, issuer);
        order.verify(template).renderInvitation(model.capture());
        order.verify(sender).send(any(EmailMessage.class));
        order.verify(issuer).recordDelivery(issued.invitation().id(), issued.invitation().tokenHash(), true, NOW);
        assertThat(model.getValue().validityDays()).isEqualTo(7);
        assertThat(model.getValue().expiresAt()).isEqualTo(NOW.plus(Duration.ofDays(model.getValue().validityDays())));
        assertThat(model.getValue().token()).isEqualTo(issued.token());
        assertThat(view.deliveryStatus()).isEqualTo(DeliveryStatus.SENT);
    }

    @Test
    @DisplayName("falha do provedor: registra FAILED e lança 502 com o id do convite")
    void recordsFailure() {
        when(template.renderInvitation(any())).thenReturn(new RenderedEmail("assunto", "<p>oi</p>", "oi"));
        when(sender.send(any())).thenThrow(new EmailDeliveryException("sem rede"));

        assertThatThrownBy(() -> dispatcher.deliver(issued)).isInstanceOfSatisfying(InvitationDeliveryFailedException.class, e -> {
            assertThat(e.invitationId()).isEqualTo(issued.invitation().id());
            assertThat(e.code()).isEqualTo("EMAIL_DELIVERY_FAILED");
            assertThat(e.getMessage()).doesNotContain(issued.token());
        });
        verify(issuer).recordDelivery(issued.invitation().id(), issued.invitation().tokenHash(), false, NOW);
    }

    @Test
    @DisplayName("falha ao montar a mensagem também vira FAILED reenviável")
    void treatsRenderFailureAsDeliveryFailure() {
        when(template.renderInvitation(any())).thenThrow(new IllegalStateException("template"));
        assertThatThrownBy(() -> dispatcher.deliver(issued)).isInstanceOf(InvitationDeliveryFailedException.class);
        verify(issuer).recordDelivery(eq(issued.invitation().id()), eq(issued.invitation().tokenHash()), eq(false), any());
        org.mockito.Mockito.verifyNoInteractions(sender);
        org.mockito.Mockito.verify(issuer, org.mockito.Mockito.never()).recordDelivery(any(), any(), eq(true), any());
    }
}
