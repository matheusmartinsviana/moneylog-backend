package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("IPs já vistos por token")
class QuickTokenSeenIpsTest {

    private final QuickTokenSeenIps seen = new QuickTokenSeenIps();
    private final UUID token = UUID.randomUUID();

    @Test
    @DisplayName("o primeiro uso de todos não alerta; um IP diferente alerta uma vez e depois é conhecido")
    void deveAlertarSoNoIpNovo() {
        assertThat(seen.registerAndCheckNew(token, "203.0.113.0", null)).isFalse();
        assertThat(seen.registerAndCheckNew(token, "203.0.113.0", "203.0.113.0")).isFalse();
        assertThat(seen.registerAndCheckNew(token, "198.51.100.0", "203.0.113.0")).isTrue();
        assertThat(seen.registerAndCheckNew(token, "198.51.100.0", "198.51.100.0")).isFalse();
        assertThat(seen.registerAndCheckNew(token, "203.0.113.0", "198.51.100.0")).isFalse();
    }

    @Test
    @DisplayName("depois de reiniciar, o último IP gravado conta como conhecido")
    void deveSemearComUltimoIpGravado() {
        assertThat(seen.registerAndCheckNew(token, "203.0.113.0", "203.0.113.0")).isFalse();
        assertThat(seen.registerAndCheckNew(UUID.randomUUID(), "198.51.100.0", "203.0.113.0")).isTrue();
    }

    @Test
    @DisplayName("IP ilegível não alerta nem quebra")
    void deveIgnorarIpNulo() {
        assertThat(seen.registerAndCheckNew(token, null, "203.0.113.0")).isFalse();
    }
}
