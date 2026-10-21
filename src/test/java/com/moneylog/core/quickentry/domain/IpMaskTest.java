package com.moneylog.core.quickentry.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Máscara do IP (LGPD): IPv4 sem o último octeto, IPv6 sem os 80 bits finais")
class IpMaskTest {

    @Test
    void deveMascararIpv4() {
        assertThat(IpMask.mask("203.0.113.77")).isEqualTo("203.0.113.0");
        assertThat(IpMask.mask(" 10.1.2.3 ")).isEqualTo("10.1.2.0");
    }

    @Test
    void deveMascararIpv6() {
        assertThat(IpMask.mask("2001:db8:abcd:12:1:2:3:4")).isEqualTo("2001:db8:abcd:0:0:0:0:0");
        assertThat(IpMask.mask("2001:db8::1")).isEqualTo("2001:db8:0:0:0:0:0:0");
    }

    @Test
    void deveTratarIpv4MapeadoComoIpv4() {
        assertThat(IpMask.mask("::ffff:203.0.113.77")).isEqualTo("203.0.113.0");
    }

    @Test
    void deveSerIdempotente() {
        assertThat(IpMask.mask(IpMask.mask("198.51.100.9"))).isEqualTo("198.51.100.0");
        assertThat(IpMask.mask(IpMask.mask("2001:db8:abcd:12::4"))).isEqualTo("2001:db8:abcd:0:0:0:0:0");
    }

    @Test
    void deveDescartarEntradaInvalida() {
        assertThat(IpMask.mask(null)).isNull();
        assertThat(IpMask.mask("")).isNull();
        assertThat(IpMask.mask("999.1.1.1")).isNull();
        assertThat(IpMask.mask("nao-e-ip")).isNull();
        assertThat(IpMask.mask("fe80::1%eth0")).isNull();
    }

    @Test
    void tokenEAuditoriaNuncaGuardamIpCompleto() {
        java.time.Instant now = java.time.Instant.parse("2026-10-21T12:00:00Z");
        QuickEntryToken token = QuickEntryToken.issue(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "a", "abcd1234", "a".repeat(64), now, now.plusSeconds(60)).usedAt(now, "203.0.113.77", "ua");
        assertThat(token.lastUsedIp()).isEqualTo("203.0.113.0");
        QuickEntryAudit audit = new QuickEntryAudit(java.util.UUID.randomUUID(), java.util.UUID.randomUUID(), java.util.UUID.randomUUID(),
                "203.0.113.77", now);
        assertThat(audit.ip()).isEqualTo("203.0.113.0");
    }
}
