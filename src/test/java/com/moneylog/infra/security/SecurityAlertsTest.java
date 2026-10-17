package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.moneylog.core.notification.app.SecurityAlertService;
import com.moneylog.core.notification.domain.SecurityEvent;

@DisplayName("Disparo assíncrono e best-effort dos alertas")
class SecurityAlertsTest {

    @Test
    @DisplayName("falha do push é engolida e o chamador não é afetado")
    void naoDevePropagarFalha() {
        SecurityAlertService service = mock(SecurityAlertService.class);
        doThrow(new IllegalStateException("boom")).when(service).notify(any(), any(), any());
        SecurityAlerts alerts = new SecurityAlerts(service, Runnable::run);

        assertThatCode(() -> alerts.raise(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).doesNotThrowAnyException();
        verify(service).notify(SecurityEvent.NEW_LOGIN, "203.0.113.5", null);
    }

    @Test
    @DisplayName("executor recusando a tarefa não derruba o login")
    void naoDevePropagarRejeicao() {
        SecurityAlerts alerts = new SecurityAlerts(mock(SecurityAlertService.class), task -> {
            throw new java.util.concurrent.RejectedExecutionException();
        });

        assertThatCode(() -> alerts.raise(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("desligado não faz nada")
    void deveIgnorarQuandoDesligado() {
        assertThatCode(() -> SecurityAlerts.disabled().raise(SecurityEvent.NEW_LOGIN, "203.0.113.5", null)).doesNotThrowAnyException();
    }
}
