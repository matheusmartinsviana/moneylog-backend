package com.moneylog.core.notification.app;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;

import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.domain.User;
import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSender.PushMessage;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.notification.domain.SecurityEvent;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;

@DisplayName("Alertas de segurança por push")
class SecurityAlertServiceTest {

    private final UserGateway users = mock(UserGateway.class);
    private final PushSubscriptionGateway subscriptions = mock(PushSubscriptionGateway.class);
    private final PushSender sender = mock(PushSender.class);
    private final IdGenerator ids = UUID::randomUUID;
    private final UUID ownerId = UUID.randomUUID();
    private Instant now = Instant.parse("2026-10-10T15:00:00Z");
    private final TimeProvider time = new TimeProvider() {
        @Override
        public Instant now() {
            return now;
        }

        @Override
        public LocalDate today() {
            return LocalDate.of(2026, 10, 10);
        }

        @Override
        public ZoneId zone() {
            return ZoneId.of("UTC");
        }
    };
    private SecurityAlertService service;
    private PushSubscription ownerSubscription;

    @BeforeEach
    void preparar() {
        service = new SecurityAlertService(users, subscriptions, sender, ids, time, Duration.ofMinutes(10));
        ownerSubscription = new PushSubscription(UUID.randomUUID(), ownerId, "https://push.example/dono", "k", "a", "ua", now);
        when(sender.enabled()).thenReturn(true);
        when(users.findOwner()).thenReturn(Optional.of(new User(ownerId, "dono@example.com")));
        when(subscriptions.listByUser(ownerId)).thenReturn(List.of(ownerSubscription));
        when(sender.send(any(), any())).thenReturn(PushSender.Result.SENT);
    }

    @Test
    @DisplayName("envia só para as inscrições do dono, com kind, titleKey e IP mascarado, sem frase pronta")
    void deveEnviarSoParaODono() {
        UUID other = UUID.randomUUID();
        PushSubscription foreign = new PushSubscription(UUID.randomUUID(), other, "https://push.example/outro", "k", "a", "ua", now);
        when(subscriptions.listByUser(other)).thenReturn(List.of(foreign));

        int delivered = service.notify(SecurityEvent.LOGIN_BLOCKED, "203.0.113.5", "ip");

        assertThat(delivered).isEqualTo(1);
        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender).send(eq(ownerSubscription), message.capture());
        verify(sender, never()).send(eq(foreign), any());
        verify(subscriptions, never()).listByUser(other);
        assertThat(message.getValue().kind()).isEqualTo("SECURITY_ALERT");
        assertThat(message.getValue().titleKey()).isEqualTo("security.login_blocked");
        assertThat(message.getValue().params()).containsOnly(java.util.Map.entry("ip", "203.0.x.x"), java.util.Map.entry("scope", "ip"));
    }

    @Test
    @DisplayName("evento sem IP nem escopo não traz parâmetros")
    void deveOmitirParametrosAusentes() {
        service.notify(SecurityEvent.PASSWORD_RESET, null, null);

        ArgumentCaptor<PushMessage> message = ArgumentCaptor.forClass(PushMessage.class);
        verify(sender).send(eq(ownerSubscription), message.capture());
        assertThat(message.getValue().params()).isEmpty();
    }

    @Test
    @DisplayName("throttle: o mesmo evento não repete em 10 minutos, mas repete depois e outro evento não é afetado")
    void deveLimitarPorTipo() {
        assertThat(service.notify(SecurityEvent.LOGIN_BLOCKED, "203.0.113.5", "ip")).isEqualTo(1);
        now = now.plus(Duration.ofMinutes(9));
        assertThat(service.notify(SecurityEvent.LOGIN_BLOCKED, "203.0.113.6", "ip")).isZero();
        assertThat(service.notify(SecurityEvent.NEW_LOGIN, "203.0.113.6", null)).isEqualTo(1);
        now = now.plus(Duration.ofMinutes(1));
        assertThat(service.notify(SecurityEvent.LOGIN_BLOCKED, "203.0.113.7", "ip")).isEqualTo(1);
    }

    @Test
    @DisplayName("push desligado: não consulta ninguém nem envia")
    void naoDeveFazerNadaComPushDesligado() {
        when(sender.enabled()).thenReturn(false);

        assertThat(service.notify(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).isZero();

        verify(users, never()).findOwner();
        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("dono sem inscrição: não envia e não consome o throttle")
    void naoDeveConsumirThrottleSemInscricao() {
        when(subscriptions.listByUser(ownerId)).thenReturn(List.of());
        assertThat(service.notify(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).isZero();

        when(subscriptions.listByUser(ownerId)).thenReturn(List.of(ownerSubscription));
        assertThat(service.notify(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).isEqualTo(1);
    }

    @Test
    @DisplayName("sem dono: não envia")
    void naoDeveEnviarSemDono() {
        when(users.findOwner()).thenReturn(Optional.empty());

        assertThat(service.notify(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).isZero();

        verify(sender, never()).send(any(), any());
    }

    @Test
    @DisplayName("inscrição GONE é removida pelo endpoint, como no despacho de notificações")
    void deveRemoverInscricaoMorta() {
        when(sender.send(any(), any())).thenReturn(PushSender.Result.GONE);

        assertThat(service.notify(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).isZero();

        verify(subscriptions).deleteByEndpoint(ownerSubscription.endpoint());
    }
}
