package com.moneylog.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Máscara de IP dos alertas de segurança")
class IpMaskTest {

    @Test
    @DisplayName("IPv4 mantém só os dois primeiros octetos")
    void deveMascararIpv4() {
        assertThat(IpMask.mask("203.0.113.77")).isEqualTo("203.0.x.x");
    }

    @Test
    @DisplayName("IPv6 mantém só os dois primeiros grupos")
    void deveMascararIpv6() {
        assertThat(IpMask.mask("2001:0db8:85a3::8a2e:370:7334")).isEqualTo("2001:db8::");
    }

    @Test
    @DisplayName("IPv6 mapeado de IPv4 é tratado como IPv4")
    void deveTratarMapeado() {
        assertThat(IpMask.mask("::ffff:198.51.100.9")).isEqualTo("198.51.x.x");
    }

    @Test
    @DisplayName("entrada inválida, nome de host ou nula vira unknown sem resolver DNS")
    void deveRejeitarInvalidos() {
        assertThat(IpMask.mask(null)).isEqualTo("unknown");
        assertThat(IpMask.mask("")).isEqualTo("unknown");
        assertThat(IpMask.mask("exemplo.com")).isEqualTo("unknown");
        assertThat(IpMask.mask("999.1.1.1")).isEqualTo("unknown");
    }

    @Test
    @DisplayName("os nomes dos eventos viram security.<evento em minúsculas>")
    void deveDerivarTitleKey() {
        assertThat(SecurityEvent.LOGIN_BLOCKED.titleKey()).isEqualTo("security.login_blocked");
        assertThat(SecurityEvent.NEW_LOGIN.titleKey()).isEqualTo("security.new_login");
        assertThat(SecurityEvent.SETUP_COMPLETED.titleKey()).isEqualTo("security.setup_completed");
        assertThat(SecurityEvent.PASSWORD_RESET.titleKey()).isEqualTo("security.password_reset");
    }
}
