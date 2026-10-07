package com.moneylog.infra.backup;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.io.TempDir;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.backup.app.BackupException;
import com.moneylog.core.backup.app.StoredBackup;

/** S3 falso em memória (com.sun.net.httpserver): sem rede externa e sem credencial real. */
@DisplayName("Armazenamento S3 contra um S3 falso")
class S3BackupStorageTest {

    private static final Clock CLOCK = Clock.fixed(Instant.parse("2026-10-07T03:00:00Z"), ZoneOffset.UTC);
    private static final S3Credentials FAKE = new S3Credentials("AKIAFAKEFAKEFAKEFAKE", "fake-secret-only-for-tests", null);

    private HttpServer server;
    private final Map<String, byte[]> objects = new ConcurrentHashMap<>();
    private final List<String> authorizations = new ArrayList<>();
    private int pageSize = 1000;

    @TempDir
    Path dir;

    @BeforeEach
    void start() throws IOException {
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/", this::handle);
        server.start();
    }

    @AfterEach
    void stop() {
        server.stop(0);
    }

    private String endpoint() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    private S3BackupStorage storage() {
        return new S3BackupStorage(endpoint(), "sa-east-1", "bucket-teste", S3CredentialsProvider.fixed(FAKE), CLOCK);
    }

    private void handle(HttpExchange ex) throws IOException {
        String auth = ex.getRequestHeaders().getFirst("Authorization");
        authorizations.add(auth);
        boolean signed = auth != null && auth.startsWith("AWS4-HMAC-SHA256 Credential=AKIAFAKEFAKEFAKEFAKE/20261007/sa-east-1/s3/aws4_request")
                && auth.contains("SignedHeaders=host;x-amz-content-sha256;x-amz-date") && auth.contains("Signature=")
                && "20261007T030000Z".equals(ex.getRequestHeaders().getFirst("x-amz-date"));
        if (!signed) {
            reply(ex, 403, "");
            return;
        }
        String path = ex.getRequestURI().getRawPath();
        String bucketPrefix = "/bucket-teste";
        String method = ex.getRequestMethod();
        if (path.equals(bucketPrefix) && method.equals("GET")) {
            list(ex);
            return;
        }
        String key = java.net.URLDecoder.decode(path.substring(bucketPrefix.length() + 1), StandardCharsets.UTF_8);
        switch (method) {
            case "PUT" -> {
                objects.put(key, ex.getRequestBody().readAllBytes());
                reply(ex, 200, "");
            }
            case "GET" -> {
                byte[] body = objects.get(key);
                if (body == null) {
                    reply(ex, 404, "<Error><Code>NoSuchKey</Code></Error>");
                } else {
                    ex.sendResponseHeaders(200, body.length);
                    ex.getResponseBody().write(body);
                    ex.close();
                }
            }
            case "DELETE" -> {
                objects.remove(key);
                reply(ex, 204, "");
            }
            default -> reply(ex, 405, "");
        }
    }

    private void list(HttpExchange ex) throws IOException {
        Map<String, String> q = new TreeMap<>();
        for (String pair : ex.getRequestURI().getRawQuery().split("&")) {
            String[] kv = pair.split("=", 2);
            q.put(kv[0], java.net.URLDecoder.decode(kv.length > 1 ? kv[1] : "", StandardCharsets.UTF_8));
        }
        String prefix = q.getOrDefault("prefix", "");
        List<String> keys = objects.keySet().stream().filter(k -> k.startsWith(prefix)).sorted().toList();
        int from = q.containsKey("continuation-token") ? Integer.parseInt(q.get("continuation-token")) : 0;
        int to = Math.min(keys.size(), from + pageSize);
        StringBuilder xml = new StringBuilder("<?xml version=\"1.0\"?><ListBucketResult xmlns=\"http://s3.amazonaws.com/doc/2006-03-01/\">");
        for (String k : keys.subList(from, to)) {
            xml.append("<Contents><Key>").append(k).append("</Key><LastModified>2026-10-06T10:00:00.000Z</LastModified><Size>")
                    .append(objects.get(k).length).append("</Size></Contents>");
        }
        boolean truncated = to < keys.size();
        xml.append("<IsTruncated>").append(truncated).append("</IsTruncated>");
        if (truncated) {
            xml.append("<NextContinuationToken>").append(to).append("</NextContinuationToken>");
        }
        reply(ex, 200, xml.append("</ListBucketResult>").toString());
    }

    private static void reply(HttpExchange ex, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        ex.sendResponseHeaders(status, bytes.length == 0 ? -1 : bytes.length);
        if (bytes.length > 0) {
            ex.getResponseBody().write(bytes);
        }
        ex.close();
    }

    @Test
    @DisplayName("grava, lista (com paginação), baixa e remove com prefixo por data, sempre assinado")
    void deveFazerCicloCompleto() throws Exception {
        S3BackupStorage s = storage();
        Path src = dir.resolve("src");
        Files.writeString(src, "conteudo do backup");
        pageSize = 2;

        s.put("moneylog/2026/10/05/a b.dump", src);
        s.put("moneylog/2026/10/06/b.dump", src);
        s.put("moneylog/2026/10/07/c.dump", src);
        s.put("outro-app/z.dump", src);

        assertThat(objects).containsKey("moneylog/2026/10/05/a b.dump");
        List<StoredBackup> listed = s.list("moneylog/");
        assertThat(listed).extracting(StoredBackup::key)
                .containsExactly("moneylog/2026/10/05/a b.dump", "moneylog/2026/10/06/b.dump", "moneylog/2026/10/07/c.dump");
        assertThat(listed.get(0).sizeBytes()).isEqualTo("conteudo do backup".length());
        assertThat(listed.get(0).lastModified()).isEqualTo(Instant.parse("2026-10-06T10:00:00Z"));
        Path out = dir.resolve("out");
        s.get("moneylog/2026/10/06/b.dump", out);
        assertThat(Files.readString(out)).isEqualTo("conteudo do backup");
        s.delete("moneylog/2026/10/05/a b.dump");
        assertThat(objects).doesNotContainKey("moneylog/2026/10/05/a b.dump").containsKey("outro-app/z.dump");
        assertThat(authorizations).allSatisfy(a -> assertThat(a).doesNotContain("fake-secret-only-for-tests"));
    }

    @Test
    @DisplayName("objeto inexistente e credencial recusada viram BackupException sem vazar a credencial")
    void deveFalharSemVazar() {
        assertThatThrownBy(() -> storage().get("moneylog/nao-existe", dir.resolve("o"))).isInstanceOf(BackupException.class)
                .hasMessageContaining("404");
        S3BackupStorage semAssinatura = new S3BackupStorage(endpoint(), "us-east-1", "bucket-teste", S3CredentialsProvider.fixed(FAKE), CLOCK);
        assertThatThrownBy(() -> semAssinatura.list("moneylog/")).isInstanceOf(BackupException.class).hasMessageNotContaining("fake-secret");
        assertThat(FAKE.toString()).doesNotContain("fake-secret", "AKIA");
    }

    @Test
    @DisplayName("credenciais da role da instância (IMDSv2) vêm do endpoint de metadados falso")
    void deveLerCredenciaisDaRole() throws Exception {
        HttpServer imds = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        imds.createContext("/latest/api/token", ex -> reply(ex, "PUT".equals(ex.getRequestMethod()) ? 200 : 405, "tok"));
        imds.createContext("/latest/meta-data/iam/security-credentials/", ex -> {
            if (!"tok".equals(ex.getRequestHeaders().getFirst("X-aws-ec2-metadata-token"))) {
                reply(ex, 401, "");
            } else if (ex.getRequestURI().getPath().endsWith("/")) {
                reply(ex, 200, "moneylog-role");
            } else {
                reply(ex, 200, "{\"AccessKeyId\":\"ASIAFAKE\",\"SecretAccessKey\":\"s3cr3t\",\"Token\":\"tkn\",\"Expiration\":\"2099-01-01T00:00:00Z\"}");
            }
        });
        imds.start();
        try {
            S3Credentials c = new InstanceMetadataCredentialsProvider("http://127.0.0.1:" + imds.getAddress().getPort(), new ObjectMapper()).credentials();
            assertThat(c.accessKey()).isEqualTo("ASIAFAKE");
            assertThat(c.sessionToken()).isEqualTo("tkn");
        } finally {
            imds.stop(0);
        }
    }
}
