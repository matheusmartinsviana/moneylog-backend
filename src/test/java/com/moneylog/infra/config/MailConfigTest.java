package com.moneylog.infra.config;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.env.MockEnvironment;

import com.moneylog.infra.mail.DisabledEmailSender;
import com.moneylog.infra.mail.FakeEmailSender;
import com.moneylog.infra.mail.LogEmailSender;
import com.moneylog.infra.mail.config.MailProperties;

@DisplayName("MailConfig")
class MailConfigTest {

    private final MailConfig config = new MailConfig();

    @Test
    @DisplayName("fake no perfil prod impede a subida")
    void deveBloquearFakeEmProducao() {
        MailConfig config = new MailConfig();
        MockEnvironment prod = new MockEnvironment();
        prod.setActiveProfiles("prod");
        assertThatThrownBy(() -> config.mailProperties("fake", "", "", "", "", "", prod))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("produção");
        MockEnvironment dev = new MockEnvironment();
        dev.setActiveProfiles("dev");
        assertThat(config.mailProperties("fake", "", "", "", "", "", dev).provider()).isEqualTo("fake");
    }

    @Test
    @DisplayName("MAIL_PROVIDER escolhe o adaptador")
    void deveEscolherAdaptador() {
        MockEnvironment env = new MockEnvironment();
        assertThat(config.emailSender(new MailProperties("none", "", "", "", "", ""), env)).isInstanceOf(DisabledEmailSender.class);
        assertThat(config.emailSender(new MailProperties("log", "", "", "", "", ""), env)).isInstanceOf(LogEmailSender.class);
        assertThat(config.emailSender(new MailProperties("fake", "", "", "", "", ""), env)).isInstanceOf(FakeEmailSender.class);
    }

    @Test
    @DisplayName("ses monta o cliente com região e fecha sem chamar a rede")
    void deveMontarClienteSes() throws Exception {
        var properties = new MailProperties("ses", "convite@moneylog.mviana.tech", "c@mviana.tech", "us-east-1", "moneylog-prod", "");
        var sender = config.emailSender(properties, new MockEnvironment());
        assertThat(sender).isInstanceOf(com.moneylog.infra.mail.SesEmailSender.class);
        ((AutoCloseable) sender).close();
    }
}
