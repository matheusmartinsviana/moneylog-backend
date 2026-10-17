package com.moneylog.infra.ai.llm;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.List;
import java.util.Map;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.ai.app.LlmClient;

public class AnthropicLlmClient implements LlmClient {

    static final String DEFAULT_BASE_URL = "https://api.anthropic.com";
    static final String API_VERSION = "2023-06-01";

    private final HttpClient http;
    private final ObjectMapper mapper;
    private final URI endpoint;
    private final String apiKey;
    private final String model;
    private final Duration timeout;

    public AnthropicLlmClient(ObjectMapper mapper, String baseUrl, String apiKey, String model, Duration timeout) {
        this.mapper = mapper;
        String base = baseUrl == null || baseUrl.isBlank() ? DEFAULT_BASE_URL : trimTrailingSlashes(baseUrl.strip());
        this.endpoint = URI.create(base + "/v1/messages");
        this.apiKey = apiKey;
        this.model = model;
        this.timeout = timeout;
        this.http = HttpClient.newBuilder().connectTimeout(timeout).build();
    }

    private static String trimTrailingSlashes(String text) {
        int end = text.length();
        while (end > 0 && text.charAt(end - 1) == '/') {
            end--;
        }
        return text.substring(0, end);
    }

    @Override
    public LlmResponse complete(LlmRequest request) throws LlmException {
        String body = mapper.writeValueAsString(Map.of(
                "model", model,
                "max_tokens", request.maxTokens(),
                "system", request.system(),
                "messages", List.of(Map.of("role", "user", "content", request.userContent()))));
        HttpRequest httpRequest = HttpRequest.newBuilder(endpoint).timeout(timeout)
                .header("content-type", "application/json").header("x-api-key", apiKey).header("anthropic-version", API_VERSION)
                .POST(HttpRequest.BodyPublishers.ofString(body, StandardCharsets.UTF_8)).build();
        HttpResponse<String> response;
        try {
            response = http.send(httpRequest, HttpResponse.BodyHandlers.ofString(StandardCharsets.UTF_8));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new LlmException("Chamada ao provedor interrompida.", false);
        } catch (IOException e) {
            throw new LlmException("Falha de rede ou timeout ao chamar o provedor.", true);
        }
        int status = response.statusCode();
        if (status == 429 || status >= 500) {
            throw new LlmException("O provedor respondeu com erro transitório (" + status + ").", true);
        }
        if (status < 200 || status >= 300) {
            throw new LlmException("O provedor recusou a chamada (" + status + ").", false);
        }
        return parse(response.body());
    }

    private LlmResponse parse(String body) throws LlmException {
        try {
            JsonNode root = mapper.readTree(body);
            JsonNode content = root.path("content");
            StringBuilder text = new StringBuilder();
            if (content.isArray()) {
                for (JsonNode block : content) {
                    if ("text".equals(block.path("type").asString(""))) {
                        text.append(block.path("text").asString(""));
                    }
                }
            }
            if (text.isEmpty()) {
                throw new LlmException("Resposta do provedor sem texto.", false);
            }
            return new LlmResponse(text.toString(), root.path("usage").path("input_tokens").asInt(0),
                    root.path("usage").path("output_tokens").asInt(0));
        } catch (LlmException e) {
            throw e;
        } catch (RuntimeException e) {
            throw new LlmException("Resposta do provedor ilegível.", false);
        }
    }
}
