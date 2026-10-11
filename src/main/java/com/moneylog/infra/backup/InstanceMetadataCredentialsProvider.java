package com.moneylog.infra.backup;

import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.time.Instant;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.backup.app.BackupException;

public class InstanceMetadataCredentialsProvider implements S3CredentialsProvider {

    private static final Duration TIMEOUT = Duration.ofSeconds(3);

    private final String endpoint;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(TIMEOUT).build();
    private final ObjectMapper mapper;
    private S3Credentials cached;
    private Instant expiresAt = Instant.EPOCH;

    public InstanceMetadataCredentialsProvider(String endpoint, ObjectMapper mapper) {
        this.endpoint = endpoint.endsWith("/") ? endpoint.substring(0, endpoint.length() - 1) : endpoint;
        this.mapper = mapper;
    }

    @Override
    public synchronized S3Credentials credentials() {
        if (cached != null && Instant.now().isBefore(expiresAt.minusSeconds(300))) {
            return cached;
        }
        try {
            String token = send(HttpRequest.newBuilder(URI.create(endpoint + "/latest/api/token")).timeout(TIMEOUT)
                    .header("X-aws-ec2-metadata-token-ttl-seconds", "300").PUT(HttpRequest.BodyPublishers.noBody()).build());
            String base = endpoint + "/latest/meta-data/iam/security-credentials/";
            String role = send(get(base, token)).strip();
            JsonNode json = mapper.readTree(send(get(base + role, token)));
            cached = new S3Credentials(json.get("AccessKeyId").asString(), json.get("SecretAccessKey").asString(), json.get("Token").asString());
            expiresAt = Instant.parse(json.get("Expiration").asString());
            return cached;
        } catch (IOException | RuntimeException e) {
            throw new BackupException("não foi possível obter as credenciais da role da instância", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("interrompido ao obter as credenciais da role da instância", e);
        }
    }

    private static HttpRequest get(String url, String token) {
        return HttpRequest.newBuilder(URI.create(url)).timeout(TIMEOUT).header("X-aws-ec2-metadata-token", token).GET().build();
    }

    private String send(HttpRequest request) throws IOException, InterruptedException {
        HttpResponse<String> response = http.send(request, HttpResponse.BodyHandlers.ofString());
        if (response.statusCode() != 200) {
            throw new IOException("IMDS respondeu " + response.statusCode());
        }
        return response.body();
    }
}
