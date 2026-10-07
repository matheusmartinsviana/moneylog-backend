package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.mock.web.MockFilterChain;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.web.firewall.RequestRejectedException;
import org.springframework.security.web.firewall.RequestRejectedHandler;

@DisplayName("QueryStringGuardFilter (BUG-QA-05)")
class QueryStringGuardFilterTest {

    @ParameterizedTest
    @ValueSource(strings = {"q=ab%ZZ", "q=ab%", "q=ab%4", "q=%C0%AF", "q=%E0%80", "q=%FF", "q=%１１"})
    @DisplayName("percent-encoding inválido ou UTF-8 malformado é recusado")
    void invalido(String query) {
        assertThat(QueryStringGuardFilter.validPercentEncoding(query)).as(query).isFalse();
    }

    @ParameterizedTest
    @ValueSource(strings = {"q=a%20b", "q=%C3%A7%C3%A3o", "q=100%25", "q=%F0%9F%98%80", "q=a+b&limit=3", "q=ação%20x"})
    @DisplayName("percent-encoding válido passa")
    void valido(String query) {
        assertThat(QueryStringGuardFilter.validPercentEncoding(query)).as(query).isTrue();
    }

    @Test
    @DisplayName("query inválida vai ao tratador de recusa e a cadeia não segue")
    void recusa() throws Exception {
        RequestRejectedHandler handler = mock(RequestRejectedHandler.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/search");
        request.setQueryString("q=ab%ZZ");
        MockFilterChain chain = mock(MockFilterChain.class);
        new QueryStringGuardFilter(handler).doFilter(request, new MockHttpServletResponse(), chain);
        verify(handler).handle(any(), any(), any(RequestRejectedException.class));
        verify(chain, never()).doFilter(any(), any());
    }

    @Test
    @DisplayName("query válida segue a cadeia")
    void segue() throws Exception {
        RequestRejectedHandler handler = mock(RequestRejectedHandler.class);
        MockHttpServletRequest request = new MockHttpServletRequest("GET", "/v1/search");
        request.setQueryString("q=a%20b");
        MockFilterChain chain = mock(MockFilterChain.class);
        new QueryStringGuardFilter(handler).doFilter(request, new MockHttpServletResponse(), chain);
        verify(chain).doFilter(any(), any());
        verify(handler, never()).handle(any(), any(), any());
    }
}
