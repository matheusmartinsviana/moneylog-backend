package com.moneylog.infra.mail;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import com.moneylog.infra.mail.config.MailProperties;

@DisplayName("Configuração de e-mail")
class MailPropertiesTest {

    private static final String FROM = "convite@moneylog.mviana.tech";

    @Test
    @DisplayName("sem provedor informado, desliga o e-mail")
    void deveAssumirNone() {
        assertThat(new MailProperties("", "", "", "", "", "").provider()).isEqualTo("none");
        assertThat(new MailProperties(null, null, null, null, null, null).appBaseUrl()).isEqualTo("https://moneylog.mviana.tech");
    }

    @Test
    @DisplayName("ses sem MAIL_FROM ou sem região falha com mensagem clara")
    void deveExigirFromERegiaoNoSes() {
        assertThatThrownBy(() -> new MailProperties("ses", "", "", "us-east-1", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_FROM");
        assertThatThrownBy(() -> new MailProperties("ses", FROM, "", "", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_SES_REGION");
        assertThatThrownBy(() -> new MailProperties("ses", FROM, "", "nao-e-regiao", "", ""))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_SES_REGION");
    }

    @Test
    @DisplayName("ses completo e provedor desconhecido")
    void deveAceitarSesCompletoERecusarDesconhecido() {
        MailProperties ok = new MailProperties(" SES ", FROM, "c@mviana.tech", "us-east-1", "moneylog-prod", "https://moneylog.mviana.tech/");
        assertThat(ok.provider()).isEqualTo("ses");
        assertThat(ok.appBaseUrl()).isEqualTo("https://moneylog.mviana.tech");
        assertThat(ok.sesConfigurationSet()).isEqualTo("moneylog-prod");
        assertThatThrownBy(() -> new MailProperties("smtp", "", "", "", "", "")).isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_PROVIDER");
    }

    @Test
    @DisplayName("remetente e Reply-To com mais de um endereço ou com cabeçalho injetado são recusados")
    void deveRecusarEnderecoInvalido() {
        assertThatThrownBy(() -> new MailProperties("log", "a@x.com, b@x.com", "", "", "", "")).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MailProperties("log", "", "a@x.com\nBcc: b@x.com", "", "", "")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("a base do link precisa ser https (http só em localhost)")
    void deveExigirHttps() {
        assertThatThrownBy(() -> new MailProperties("log", "", "", "", "", "http://moneylog.mviana.tech")).isInstanceOf(IllegalStateException.class);
        assertThat(new MailProperties("log", "", "", "", "", "http://localhost:4200", false).appBaseUrl()).isEqualTo("http://localhost:4200");
        assertThat(new MailProperties("log", "", "", "", "", "http://127.0.0.1:4200", false).appBaseUrl()).isEqualTo("http://127.0.0.1:4200");
    }

    @ParameterizedTest
    @ValueSource(strings = {"http://localhost.evil.com", "https://x@evil.com", "https://moneylog.mviana.tech?q=1", "https://moneylog.mviana.tech#f",
            "https://moneylog.mviana.tech/?q", "ftp://moneylog.mviana.tech", "https://", "nao é url"})
    @DisplayName("base do link com host falso, userinfo, query, fragmento ou esquema errado é recusada")
    void deveRecusarBaseInvalida(String base) {
        assertThatThrownBy(() -> new MailProperties("log", "", "", "", "", base, false)).isInstanceOf(IllegalStateException.class).hasMessageContaining("MAIL_APP_BASE_URL");
    }

    @Test
    @DisplayName("http em localhost é recusado em produção")
    void deveRecusarHttpEmProducao() {
        assertThatThrownBy(() -> new MailProperties("log", "", "", "", "", "http://localhost", true)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> new MailProperties("log", "", "", "", "", "http://localhost:4200")).isInstanceOf(IllegalStateException.class);
    }

    @Test
    @DisplayName("mensagem de provedor inválido não cita fake")
    void naoCitaFake() {
        assertThatThrownBy(() -> new MailProperties("smtp", "", "", "", "", "")).hasMessageNotContaining("fake");
    }
}
