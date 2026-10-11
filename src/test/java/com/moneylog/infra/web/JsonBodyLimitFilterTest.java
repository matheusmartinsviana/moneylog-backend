package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;

import tools.jackson.databind.json.JsonMapper;

@DisplayName("JsonBodyLimitFilter (S-09)")
class JsonBodyLimitFilterTest {

    private static final long LIMIT = 1024;
    private final JsonBodyLimitFilter filter = new JsonBodyLimitFilter(LIMIT, JsonMapper.builder().build());

    private MockHttpServletRequest request(String contentType, int size) {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login");
        request.setContentType(contentType);
        request.setContent(new byte[size]);
        return request;
    }

    @Test
    @DisplayName("Content-Length acima do limite em JSON: 413 PAYLOAD_TOO_LARGE e a cadeia não roda")
    void deveRecusarPeloContentLength() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request("application/json", 2048), response, (req, res) -> reached.set(true));

        assertThat(response.getStatus()).isEqualTo(413);
        assertThat(response.getContentAsString()).contains("\"code\":\"PAYLOAD_TOO_LARGE\"");
        assertThat(reached).isFalse();
    }

    @Test
    @DisplayName("JSON dentro do limite passa")
    void devePassarDentroDoLimite() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request("application/json", 1024), response, (req, res) -> {
            req.getInputStream().readAllBytes();
            reached.set(true);
        });

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }

    @Test
    @DisplayName("sem Content-Length (chunked), a leitura passa do limite e falha com BodyTooLargeException")
    void deveCortarLeituraSemContentLength() {
        MockHttpServletRequest request = new MockHttpServletRequest("POST", "/v1/auth/login") {
            @Override
            public long getContentLengthLong() {
                return -1;
            }
        };
        request.setContentType("application/json");
        request.setContent(new byte[4096]);

        assertThatThrownBy(() -> filter.doFilter(request, new MockHttpServletResponse(), (req, res) -> {
            try {
                req.getInputStream().readAllBytes();
            } catch (IOException e) {
                throw e;
            }
        })).isInstanceOf(BodyTooLargeException.class);
    }

    @Test
    @DisplayName("multipart e outros tipos não são limitados pelo filtro (têm limites próprios)")
    void naoDeveAfetarUploads() throws Exception {
        MockHttpServletResponse response = new MockHttpServletResponse();
        AtomicBoolean reached = new AtomicBoolean();

        filter.doFilter(request("multipart/form-data; boundary=x", 5_000_000), response, (req, res) -> reached.set(true));
        filter.doFilter(request("application/octet-stream", 5_000_000), response, (req, res) -> reached.set(true));

        assertThat(reached).isTrue();
        assertThat(response.getStatus()).isEqualTo(200);
    }
}
