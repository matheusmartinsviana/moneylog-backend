package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.WebApplicationType;
import org.springframework.boot.builder.SpringApplicationBuilder;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.context.ConfigurableApplicationContext;

import com.moneylog.MoneylogApplication;
import com.moneylog.util.PostgresTestcontainer;

/**
 * T22b CA9 e CA10: a aplicação <b>não sobe</b> com segredo curto ou com o cookie sem {@code Secure} fora do plano B,
 * e o log diz por quê sem imprimir o segredo.
 */
@Tag("i")
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Guardas de boot da segurança")
class StartupGuardsIT {

    /** As propriedades vão como argumentos de linha de comando, que vencem o {@code application-it.yaml}. */
    private static ConfigurableApplicationContext start(String... arguments) {
        return start(new String[] {"it"}, arguments);
    }

    private static ConfigurableApplicationContext start(String[] profiles, String... arguments) {
        String[] all = new String[arguments.length + 1];
        all[0] = "--server.port=0";
        System.arraycopy(arguments, 0, all, 1, arguments.length);
        return new SpringApplicationBuilder(MoneylogApplication.class, PostgresTestcontainer.class)
                .web(WebApplicationType.SERVLET).profiles(profiles).run(all);
    }

    @Test
    @DisplayName("JWT_SECRET com menos de 32 bytes: não sobe e o log não traz o segredo")
    void naoDeveSubirComSegredoCurto(CapturedOutput output) {
        assertThatThrownBy(() -> start("--moneylog.security.jwt-secret=segredo-muito-curto"))
                .hasStackTraceContaining("JWT_SECRET precisa ter no mínimo 32 bytes");
        assertThat(output.getAll()).doesNotContain("segredo-muito-curto");
    }

    @Test
    @DisplayName("cookie sem Secure fora do perfil dev: não sobe")
    void naoDeveSubirComCookieInseguroSemDev() {
        assertThatThrownBy(() -> start("--moneylog.auth.cookie-secure=false"))
                .hasStackTraceContaining("cookie-secure=false só é aceito com o perfil 'dev'");
    }

    @Test
    @DisplayName("cookie sem Secure com perfil dev mas origem que não é localhost: não sobe")
    void naoDeveSubirComOrigemExterna() {
        assertThatThrownBy(() -> start(new String[] {"it", "dev"}, "--moneylog.auth.cookie-secure=false",
                "--moneylog.security.allowed-origins=https://app.moneylog.test"))
                .hasStackTraceContaining("localhost");
    }

    @Test
    @DisplayName("hash do dono corrompido: não sobe")
    void naoDeveSubirComHashInvalido() {
        assertThatThrownBy(() -> start("--moneylog.owner.password-hash=a.Kxyz"))
                .hasStackTraceContaining("não é um hash BCrypt válido");
    }

    @Test
    @DisplayName("plano B válido (perfil dev e origens localhost): sobe com secure=false")
    void deveSubirNoPlanoBDeDev() {
        try (ConfigurableApplicationContext context = start(new String[] {"it", "dev"}, "--moneylog.auth.cookie-secure=false",
                "--moneylog.security.allowed-origins=http://localhost:4200")) {
            assertThat(context.isRunning()).isTrue();
        }
    }
}
