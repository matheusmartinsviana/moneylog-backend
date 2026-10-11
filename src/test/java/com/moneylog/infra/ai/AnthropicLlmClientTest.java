package com.moneylog.infra.ai;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.ai.app.LlmClient.LlmException;
import com.moneylog.core.ai.app.LlmClient.LlmRequest;
import com.moneylog.core.ai.app.LlmClient.LlmResponse;
import com.moneylog.infra.ai.llm.AnthropicLlmClient;

class AnthropicLlmClientTest {

    private static final String SENTINEL_KEY = "sk-ant-SENTINELA-123456";
    private final ObjectMapper mapper = new ObjectMapper();
    private HttpServer server;
    private final AtomicReference<String> body = new AtomicReference<>();
    private final AtomicReference<String> keyHeader = new AtomicReference<>();
    private final AtomicReference<String> versionHeader = new AtomicReference<>();
    private volatile int status = 200;
    private volatile String reply = "{\"content\":[{\"type\":\"text\",\"text\":\"{\\\"text\\\":\\\"oi\\\"}\"}],\"usage\":{\"input_tokens\":12,\"output_tokens\":5}}";

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/v1/messages", exchange -> {
            body.set(new String(exchange.getRequestBody().readAllBytes(), StandardCharsets.UTF_8));
            keyHeader.set(exchange.getRequestHeaders().getFirst("x-api-key"));
            versionHeader.set(exchange.getRequestHeaders().getFirst("anthropic-version"));
            byte[] out = reply.getBytes(StandardCharsets.UTF_8);
            exchange.getResponseHeaders().add("content-type", "application/json");
            exchange.sendResponseHeaders(status, out.length);
            exchange.getResponseBody().write(out);
            exchange.close();
        });
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private AnthropicLlmClient client() {
        return new AnthropicLlmClient(mapper, "http://127.0.0.1:" + server.getAddress().getPort() + "/", SENTINEL_KEY, "claude-test", Duration.ofSeconds(5));
    }

    @Test
    @DisplayName("envia modelo, max_tokens, sistema e o conteúdo; a chave só vai no cabeçalho; lê o uso de tokens")
    void deveMontarARequisicaoELerAResposta() throws Exception {
        LlmResponse response = client().complete(new LlmRequest("sistema", "{\"facts\":{}}", 600));

        JsonNode sent = mapper.readTree(body.get());
        assertThat(sent.path("model").asString()).isEqualTo("claude-test");
        assertThat(sent.path("max_tokens").asInt()).isEqualTo(600);
        assertThat(sent.path("system").asString()).isEqualTo("sistema");
        assertThat(sent.path("messages").get(0).path("role").asString()).isEqualTo("user");
        assertThat(sent.path("messages").get(0).path("content").asString()).isEqualTo("{\"facts\":{}}");
        assertThat(keyHeader.get()).isEqualTo(SENTINEL_KEY);
        assertThat(versionHeader.get()).isEqualTo("2023-06-01");
        assertThat(body.get()).doesNotContain(SENTINEL_KEY);
        assertThat(response.text()).isEqualTo("{\"text\":\"oi\"}");
        assertThat(response.inputTokens()).isEqualTo(12);
        assertThat(response.outputTokens()).isEqualTo(5);
    }

    @Test
    @DisplayName("401/403: erro permanente, sem eco do corpo nem da chave")
    void naoDeveRepetirAutenticacaoNemEcoarOCorpo() {
        status = 401;
        reply = "{\"error\":\"invalid x-api-key " + SENTINEL_KEY + "\"}";

        assertThatThrownBy(() -> client().complete(new LlmRequest("s", "u", 10))).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.isTransient()).isFalse();
            assertThat(e.getMessage()).doesNotContain(SENTINEL_KEY).doesNotContain("invalid");
        });
    }

    @Test
    void deveClassificarErrosTransitorios() {
        for (int code : new int[] {429, 500, 503}) {
            status = code;
            assertThatThrownBy(() -> client().complete(new LlmRequest("s", "u", 10))).isInstanceOfSatisfying(LlmException.class,
                    e -> assertThat(e.isTransient()).as(String.valueOf(code)).isTrue());
        }
    }

    @Test
    void deveTratarRespostaIlegivelOuSemTexto() {
        reply = "não é json";
        assertThatThrownBy(() -> client().complete(new LlmRequest("s", "u", 10))).isInstanceOf(LlmException.class);
        reply = "{\"content\":[]}";
        assertThatThrownBy(() -> client().complete(new LlmRequest("s", "u", 10))).isInstanceOf(LlmException.class);
    }

    @Test
    void deveTratarServidorForaDoAr() {
        int port = server.getAddress().getPort();
        server.stop(0);
        AnthropicLlmClient client = new AnthropicLlmClient(mapper, "http://127.0.0.1:" + port, SENTINEL_KEY, "m", Duration.ofSeconds(2));
        assertThatThrownBy(() -> client.complete(new LlmRequest("s", "u", 10))).isInstanceOfSatisfying(LlmException.class, e -> {
            assertThat(e.isTransient()).isTrue();
            assertThat(e.getMessage()).doesNotContain(SENTINEL_KEY);
        });
    }
}
