package com.moneylog.infra.note;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayOutputStream;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ItAuth;
import com.moneylog.util.PostgresTestcontainer;

/**
 * O app sobe de verdade (servidor real, não MockMvc) e recebe um upload acima do teto do container: a resposta é {@code application/problem+json}
 * {@code 422 VAULT_IMPORT_TOO_LARGE}, sem 500 e sem derrubar o contexto (regressão do tratador de {@code MaxUploadSizeExceededException} ambíguo).
 */
@Tag("i")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, ItAuth.class})
@DisplayName("Vault: upload acima do teto do container, com o servidor real (T112)")
class VaultUploadLimitIT {

    @LocalServerPort
    private int port;
    @Autowired
    private ItAuth auth;
    @Autowired
    private ObjectMapper objectMapper;

    private HttpResponse<String> upload(int megabytes) throws Exception {
        String boundary = "----moneylogBoundary";
        ByteArrayOutputStream body = new ByteArrayOutputStream();
        body.write(("--" + boundary + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"vault.zip\"\r\nContent-Type: application/zip\r\n\r\n")
                .getBytes(StandardCharsets.UTF_8));
        body.write(new byte[megabytes * 1024 * 1024]);
        body.write(("\r\n--" + boundary + "--\r\n").getBytes(StandardCharsets.UTF_8));
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/vault/import"))
                .header("Authorization", auth.ownerBearer()).header("Content-Type", "multipart/form-data; boundary=" + boundary)
                .POST(HttpRequest.BodyPublishers.ofByteArray(body.toByteArray())).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    @Test
    @DisplayName("o app responde; 25 MB (acima do teto do container) e 20,5 MB (acima do limite do caso de uso) são 422 VAULT_IMPORT_TOO_LARGE em problem+json")
    void deveRecusarUploadGrande() throws Exception {
        HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(),
                HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
        for (int mb : new int[] {25, 21}) {
            HttpResponse<String> response = upload(mb);
            assertThat(response.statusCode()).as("%d MB", mb).isEqualTo(422);
            assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
            JsonNode problem = objectMapper.readTree(response.body());
            assertThat(problem.get("code").asString()).isEqualTo("VAULT_IMPORT_TOO_LARGE");
            assertThat(problem.get("traceId").asString()).isNotBlank();
        }
    }
}
