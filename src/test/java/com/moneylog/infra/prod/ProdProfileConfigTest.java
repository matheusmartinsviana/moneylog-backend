package com.moneylog.infra.prod;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.file.Files;
import java.nio.file.Path;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.context.ConfigurableApplicationContext;
import org.springframework.context.annotation.Configuration;

@DisplayName("Perfil prod: configuração e segredos por arquivo")
class ProdProfileConfigTest {

    @Configuration
    static class Empty {
    }

    @TempDir
    Path secrets;

    private ConfigurableApplicationContext run(String... extra) {
        String[] base = {"--spring.profiles.active=prod", "--spring.autoconfigure.exclude=*", "--SECRETS_DIR=" + secrets,
                "--spring.main.banner-mode=off"};
        String[] all = new String[base.length + extra.length];
        System.arraycopy(base, 0, all, 0, base.length);
        System.arraycopy(extra, 0, all, base.length, extra.length);
        return new SpringApplicationBuilder(Empty.class).web(WebApplicationType.NONE).run(all);
    }

    @Test
    @DisplayName("segredo em arquivo (Docker secrets) resolve o placeholder e o valor não aparece no toString do ambiente")
    void deveLerSegredoDeArquivo() throws Exception {
        Files.writeString(secrets.resolve("JWT_SECRET"), "valor-de-teste-do-arquivo-0123456789abcdef\n");
        Files.writeString(secrets.resolve("DB_PASSWORD"), "senha-do-arquivo");
        try (ConfigurableApplicationContext ctx = run("--DB_URL=jdbc:x", "--DB_USER=u", "--MONEYLOG_ALLOWED_ORIGINS=https://x",
                "--MONEYLOG_OWNER_EMAIL=a@b.c")) {
            assertThat(ctx.getEnvironment().getProperty("moneylog.security.jwt-secret")).isEqualTo("valor-de-teste-do-arquivo-0123456789abcdef");
            assertThat(ctx.getEnvironment().getProperty("spring.datasource.password")).isEqualTo("senha-do-arquivo");
        }
    }

    @Test
    @DisplayName("prod: swagger e api-docs desligados, cookie Secure, probes ligados e log estruturado")
    void deveDefinirOPerfilProd() {
        try (ConfigurableApplicationContext ctx = run("--JWT_SECRET=x", "--DB_URL=jdbc:x", "--DB_USER=u", "--DB_PASSWORD=p",
                "--MONEYLOG_ALLOWED_ORIGINS=https://x", "--MONEYLOG_OWNER_EMAIL=a@b.c")) {
            var env = ctx.getEnvironment();
            assertThat(env.getProperty("springdoc.api-docs.enabled")).isEqualTo("false");
            assertThat(env.getProperty("springdoc.swagger-ui.enabled")).isEqualTo("false");
            assertThat(env.getProperty("moneylog.auth.cookie-secure")).isEqualTo("true");
            assertThat(env.getProperty("management.endpoint.health.probes.enabled")).isEqualTo("true");
            assertThat(env.getProperty("management.endpoints.web.exposure.include")).isEqualTo("health");
            assertThat(env.getProperty("logging.structured.format.console")).isEqualTo("ecs");
            assertThat(env.getProperty("moneylog.push.enabled")).isEqualTo("false");
            assertThat(env.getProperty("moneylog.backup.enabled")).isEqualTo("false");
            assertThat(env.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
        }
    }
}
