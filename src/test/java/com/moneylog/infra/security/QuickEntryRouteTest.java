package com.moneylog.infra.security;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;

@DisplayName("Matchers da rota do token, sem firewall")
class QuickEntryRouteTest {

    private static MockHttpServletRequest request(String method, String uri) {
        MockHttpServletRequest request = new MockHttpServletRequest(method, uri);
        request.setRequestURI(uri);
        return request;
    }

    @Test
    @DisplayName("só POST no caminho exato, ainda que codificado, é a rota do token")
    void deveReconhecerARotaDoToken() {
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/quick-entries"))).isTrue();
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/quick%2Dentries"))).isTrue();
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/%71uick%2dentries"))).isTrue();
        assertThat(QuickEntryRoute.isTokenRoute(request("GET", "/v1/quick-entries"))).isFalse();
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/quick-entries/"))).isFalse();
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/Quick-Entries"))).isFalse();
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/quick-entries;x=1"))).isFalse();
        assertThat(QuickEntryRoute.isTokenRoute(request("POST", "/v1/quick-entries%ZZ"))).isFalse();
    }

    @Test
    @DisplayName("variantes com parâmetro de caminho, codificação, caixa e barra final caem no denyAll da cadeia JWT")
    void deveNegarVariantes() {
        for (String uri : new String[] {"/v1/quick-entries", "/v1/quick-entries;x=1", "/v1;a=b/quick-entries;jsessionid=1",
                "/v1/quick%2Dentries;x=1", "/v1/quick-entries/", "/v1/QUICK-ENTRIES", "/v1/%71uick-entries", "/v1/quick-entries%ZZ",
                "/v1/quick-entries%", "/v1/quick-entries/;x=1"}) {
            assertThat(QuickEntryRoute.isAnyVariant(request("POST", uri))).as(uri).isTrue();
            assertThat(QuickEntryRoute.isAnyVariant(request("GET", uri))).as(uri).isTrue();
        }
    }

    @Test
    @DisplayName("outras rotas não são negadas")
    void naoDeveNegarOutrasRotas() {
        for (String uri : new String[] {"/v1/transactions", "/v1/quick-entry-tokens", "/v1/quick-entries/x", "/v1/auth/login"}) {
            assertThat(QuickEntryRoute.isAnyVariant(request("POST", uri))).as(uri).isFalse();
        }
    }
}
