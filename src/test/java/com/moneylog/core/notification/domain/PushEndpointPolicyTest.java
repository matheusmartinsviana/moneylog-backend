package com.moneylog.core.notification.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("PushEndpointPolicy (A3, anti-SSRF)")
class PushEndpointPolicyTest {

    @ParameterizedTest
    @ValueSource(strings = {
            "https://fcm.googleapis.com/fcm/send/abc",
            "https://updates.push.services.mozilla.com/wpush/v2/abc",
            "https://wns2-par02p.notify.windows.com/w/?token=abc",
            "https://web.push.apple.com/abc",
            "https://fcm.googleapis.com:443/fcm/send/abc"})
    @DisplayName("serviços de push conhecidos passam")
    void permitidos(String endpoint) {
        assertThat(PushEndpointPolicy.isAllowed(endpoint)).isTrue();
    }

    @ParameterizedTest
    @ValueSource(strings = {
            "http://fcm.googleapis.com/fcm/send/abc",
            "https://169.254.169.254/latest/meta-data",
            "https://127.0.0.1/x",
            "https://[::1]/x",
            "https://localhost/x",
            "https://evil.example.com/x",
            "https://fcm.googleapis.com.evil.com/x",
            "https://evilpush.apple.com.attacker.io/x",
            "https://notpush.services.mozilla.com/x",
            "https://fcm.googleapis.com:8443/x",
            "https://user:pw@fcm.googleapis.com/x",
            "https://fcm.googleapis.com@evil.com/x",
            "https://fcm.googleapis.com./x",
            "not a url",
            ""})
    @DisplayName("IP literal, porta atípica, host fora da lista, userinfo e esquema http são recusados")
    void recusados(String endpoint) {
        assertThat(PushEndpointPolicy.isAllowed(endpoint)).isFalse();
    }
}
