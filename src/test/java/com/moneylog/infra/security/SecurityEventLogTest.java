package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.spi.ILoggingEvent;
import ch.qos.logback.core.read.ListAppender;

@DisplayName("SecurityEventLog: eventos de segurança sem dado além do necessário")
class SecurityEventLogTest {

    private final ListAppender<ILoggingEvent> appender = new ListAppender<>();
    private Logger logger;

    @BeforeEach
    void attach() {
        logger = (Logger) LoggerFactory.getLogger("com.moneylog.security.events");
        appender.start();
        logger.addAppender(appender);
    }

    @AfterEach
    void detach() {
        logger.detachAppender(appender);
    }

    private List<String> messages() {
        return appender.list.stream().map(ILoggingEvent::getFormattedMessage).toList();
    }

    @Test
    @DisplayName("registra falha, bloqueio, sucesso, setup e reset com e-mail mascarado e IP")
    void deveRegistrarOsEventos() {
        SecurityEventLog.loginFailed("203.0.113.9", "carlos@example.com");
        SecurityEventLog.blocked(TooManyAttemptsException.Scope.ACCOUNT, "203.0.113.9", "carlos@example.com");
        SecurityEventLog.blocked(TooManyAttemptsException.Scope.IP, "203.0.113.9", "carlos@example.com");
        SecurityEventLog.loginSucceeded("203.0.113.9", "carlos@example.com");
        SecurityEventLog.setupFailed("203.0.113.9", "carlos@example.com");
        SecurityEventLog.ownerPasswordReset("carlos@example.com");

        assertThat(messages()).hasSize(6);
        assertThat(messages().get(0)).contains("event=login_failed", "ip=203.0.113.9", "email=c***@example.com");
        assertThat(messages().get(1)).contains("event=login_blocked", "scope=account");
        assertThat(messages().get(2)).contains("scope=ip");
        assertThat(messages().get(3)).contains("event=login_succeeded");
        assertThat(messages().get(4)).contains("event=setup_failed");
        assertThat(messages().get(5)).contains("event=owner_password_reset");
        assertThat(String.join("\n", messages())).doesNotContain("carlos@");
    }

    @Test
    @DisplayName("e-mail inválido ou com quebra de linha não injeta linhas no log")
    void deveNeutralizarEntradaHostil() {
        SecurityEventLog.loginFailed("1.1.1.1", "x@exemplo.test\nevent=login_succeeded");
        SecurityEventLog.loginFailed("1.1.1.1", "sem-arroba");
        SecurityEventLog.loginFailed("1.1.1.1", null);

        assertThat(messages().get(0)).doesNotContain("\n").contains("email=x***@exemplo.test_event=login_succeeded");
        assertThat(messages().get(1)).contains("email=***");
        assertThat(messages().get(2)).contains("email=***");
    }
}
