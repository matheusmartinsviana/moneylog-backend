package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.io.ByteArrayOutputStream;
import java.io.OutputStream;
import java.net.Socket;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.ActiveProfiles;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.NoteQa.RawZip;
import com.moneylog.util.ItAuth;
import com.moneylog.util.PostgresTestcontainer;
import com.moneylog.util.SqlFixtures;

/**
 * T113 (QA): importação com o servidor REAL (Tomcat, não MockMvc): uploads de 19, 20, 20 MB + 1 byte, 21 e 25 MB, multipart fora do padrão (muitas
 * partes, sem boundary, cabeçalhos absurdos, corpo cortado no meio, transferência em pedaços). Nunca 500; o app segue saudável; cada usuário tem a
 * sua cota de taxa (6 por minuto), então cada requisição usa um usuário novo.
 */
@Tag("i")
@ActiveProfiles("it")
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import({PostgresTestcontainer.class, ItAuth.class})
@DisplayName("QA T113: upload do vault com o servidor real")
class VaultUploadQaIT {

    private static final String B = "----qaBoundary";

    @LocalServerPort
    private int port;
    @Autowired
    private ItAuth auth;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager tm;

    private String freshBearer() {
        UUID id = UUID.randomUUID();
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(st -> SqlFixtures.insertUser(jdbc, id, "up-" + id + "@moneylog.com"));
        return auth.bearer(id);
    }

    private HttpResponse<String> post(String bearer, String contentType, byte[] body) throws Exception {
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/vault/import")).header("Authorization", bearer)
                .header("Content-Type", contentType).POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
        return HttpClient.newHttpClient().send(request, HttpResponse.BodyHandlers.ofString());
    }

    private static byte[] multipart(Map<String, byte[]> fields, byte[] file, String filename) throws Exception {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        for (Map.Entry<String, byte[]> f : fields.entrySet()) {
            out.write(("--" + B + "\r\nContent-Disposition: form-data; name=\"" + f.getKey() + "\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(f.getValue());
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        if (file != null) {
            out.write(("--" + B + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"" + filename + "\"\r\nContent-Type: application/zip\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(file);
            out.write("\r\n".getBytes(StandardCharsets.UTF_8));
        }
        out.write(("--" + B + "--\r\n").getBytes(StandardCharsets.UTF_8));
        return out.toByteArray();
    }

    /** Zip de EXATAMENTE {@code total} bytes: uma entrada STORED de enchimento (overhead fixo de 112 bytes com o nome pad.bin). */
    private static byte[] zipOfSize(long total) {
        byte[] data = new byte[(int) (total - 112)];
        new java.util.Random(1).nextBytes(data);
        byte[] zip = new RawZip().add("pad.bin".getBytes(StandardCharsets.UTF_8), data, false, true, 0).bytes();
        assertThat(zip.length).isEqualTo((int) total);
        return zip;
    }

    private void healthy() throws Exception {
        HttpResponse<String> health = HttpClient.newHttpClient().send(HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/actuator/health")).build(), HttpResponse.BodyHandlers.ofString());
        assertThat(health.statusCode()).isEqualTo(200);
    }

    @Test
    @DisplayName("19 MB e 20 MB exatos entram (200, anexo ignorado); 20 MB + 1 byte, 21 MB e 25 MB são 422 VAULT_IMPORT_TOO_LARGE em problem+json; o app segue saudável")
    void tamanhos() throws Exception {
        long mb = 1024L * 1024;
        Map<Long, Integer> expected = new LinkedHashMap<>();
        expected.put(19 * mb, 200);
        expected.put(20 * mb, 200);
        expected.put(20 * mb + 1, 422);
        expected.put(21 * mb, 422);
        expected.put(25 * mb, 422);
        for (Map.Entry<Long, Integer> e : expected.entrySet()) {
            byte[] zip = zipOfSize(e.getKey());
            long t = System.nanoTime();
            HttpResponse<String> r = post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of(), zip, "vault.zip"));
            System.out.println("QA-E6-UP " + e.getKey() + " bytes -> " + r.statusCode() + " em " + (System.nanoTime() - t) / 1_000_000 + " ms: " + r.body().substring(0, Math.min(160, r.body().length())));
            assertThat(r.statusCode()).as("%d bytes", e.getKey()).isEqualTo(e.getValue());
            JsonNode body = om.readTree(r.body());
            if (e.getValue() == 422) {
                assertThat(r.headers().firstValue("Content-Type").orElse("")).startsWith("application/problem+json");
                assertThat(body.get("code").asString()).isEqualTo("VAULT_IMPORT_TOO_LARGE");
            } else {
                assertThat(body.get("created").asInt()).isZero();
                assertThat(body.get("ignoredFiles").get(0).asString()).isEqualTo("pad.bin");
            }
        }
        healthy();
    }

    @Test
    @DisplayName("multipart fora do padrão no servidor real: 15 campos extras, sem boundary, boundary lixo, parte sem nome, nome de arquivo enorme/com .., Content-Type errado, corpo vazio: nunca 5xx")
    void multipartEstranho() throws Exception {
        byte[] zip = new RawZip().add("a.md", "oi").bytes();
        Map<String, byte[]> many = new LinkedHashMap<>();
        for (int i = 0; i < 15; i++) {
            many.put("campo" + i, ("v" + i).getBytes(StandardCharsets.UTF_8));
        }
        Map<String, HttpResponse<String>> results = new LinkedHashMap<>();
        results.put("15 campos extras", post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(many, zip, "v.zip")));
        results.put("mode como campo do corpo", post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of("mode", "RENAME".getBytes(StandardCharsets.UTF_8)), zip, "v.zip")));
        results.put("boundary errado", post(freshBearer(), "multipart/form-data; boundary=outro", multipart(Map.of(), zip, "v.zip")));
        results.put("sem arquivo", post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of("x", new byte[] {1}), null, null)));
        results.put("nome de arquivo com ..", post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of(), zip, "../../etc/passwd")));
        results.put("nome de arquivo enorme", post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of(), zip, "a".repeat(70_000) + ".zip")));
        results.put("nome com CRLF", post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of(), zip, "a\r\nX-Evil: 1.zip")));
        results.put("corpo vazio", post(freshBearer(), "multipart/form-data; boundary=" + B, new byte[0]));
        results.put("application/json", post(freshBearer(), "application/json", "{}".getBytes(StandardCharsets.UTF_8)));
        results.put("application/zip direto", post(freshBearer(), "application/zip", zip));
        results.put("text/plain", post(freshBearer(), "text/plain", "x".getBytes(StandardCharsets.UTF_8)));
        for (Map.Entry<String, HttpResponse<String>> e : results.entrySet()) {
            System.out.println("QA-E6-UP " + e.getKey() + " -> " + e.getValue().statusCode() + " " + e.getValue().body().substring(0, Math.min(120, e.getValue().body().length())));
            assertThat(e.getValue().statusCode()).as(e.getKey()).isLessThan(500);
            assertThat(e.getValue().headers().firstValue("X-Content-Type-Options").orElse("")).as(e.getKey()).isEqualTo("nosniff");
        }
        healthy();
    }

    @Test
    @DisplayName("BUG-QA-09: multipart sem boundary é 400, não 500")
    void multipartSemBoundary() throws Exception {
        HttpResponse<String> r = post(freshBearer(), "multipart/form-data", multipart(Map.of(), new RawZip().add("a.md", "oi").bytes(), "v.zip"));
        assertThat(r.statusCode()).as(r.body()).isEqualTo(400);
        HttpResponse<String> open = post(freshBearer(), "multipart/form-data; boundary=" + B,
                ("--" + B + "\r\nContent-Disposition: form-data; name=\"file\"; filename=\"a.zip\"\r\n\r\n").getBytes(StandardCharsets.UTF_8));
        assertThat(open.statusCode()).as(open.body()).isEqualTo(400);
    }

    @Test
    @DisplayName("transferência em pedaços (chunked) de 25 MB e corpo cortado no meio (Content-Length maior que o enviado): sem 500, sem derrubar o app")
    void pedacosECortado() throws Exception {
        byte[] zip = zipOfSize(25L * 1024 * 1024);
        byte[] body = multipart(Map.of(), zip, "v.zip");
        HttpRequest chunked = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/v1/vault/import")).header("Authorization", freshBearer())
                .header("Content-Type", "multipart/form-data; boundary=" + B).POST(HttpRequest.BodyPublishers.ofInputStream(() -> new ByteArrayInputStream(body))).build();
        HttpResponse<String> r = HttpClient.newHttpClient().send(chunked, HttpResponse.BodyHandlers.ofString());
        System.out.println("QA-E6-UP chunked 25 MB -> " + r.statusCode() + " " + r.body().substring(0, Math.min(120, r.body().length())));
        assertThat(r.statusCode()).isIn(413, 422);

        // corta no meio: promete 5 MB e envia 200 KB, depois fecha a conexão
        try (Socket socket = new Socket("localhost", port)) {
            socket.setSoTimeout(5_000);
            OutputStream out = socket.getOutputStream();
            out.write(("POST /v1/vault/import HTTP/1.1\r\nHost: localhost\r\nAuthorization: " + freshBearer() + "\r\nContent-Type: multipart/form-data; boundary=" + B
                    + "\r\nContent-Length: 5242880\r\n\r\n").getBytes(StandardCharsets.UTF_8));
            out.write(multipart(Map.of(), new byte[200_000], "v.zip"), 0, 200_000);
            out.flush();
        }
        Thread.sleep(500);
        healthy();
        // e o app continua importando
        HttpResponse<String> ok = post(freshBearer(), "multipart/form-data; boundary=" + B, multipart(Map.of(), new RawZip().add("a.md", "oi").bytes(), "v.zip"));
        assertThat(ok.statusCode()).isEqualTo(200);
        assertThat(om.readTree(ok.body()).get("created").asInt()).isEqualTo(1);
    }
}
