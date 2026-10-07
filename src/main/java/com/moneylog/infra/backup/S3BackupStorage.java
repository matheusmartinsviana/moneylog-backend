package com.moneylog.infra.backup;

import java.io.IOException;
import java.io.InputStream;
import java.net.URI;
import java.net.URLEncoder;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.nio.file.Files;
import java.nio.file.Path;
import java.nio.file.StandardCopyOption;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.ZoneOffset;
import java.time.format.DateTimeFormatter;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import javax.crypto.Mac;
import javax.crypto.spec.SecretKeySpec;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;

import org.w3c.dom.Document;
import org.w3c.dom.Element;
import org.w3c.dom.NodeList;
import org.xml.sax.InputSource;

import com.moneylog.core.backup.app.BackupException;
import com.moneylog.core.backup.app.BackupStorage;
import com.moneylog.core.backup.app.StoredBackup;

/**
 * Backup no S3 com JDK puro: endereçamento por path ({@code endpoint/bucket/key}) e assinatura AWS Signature V4, sem SDK (mesma linha do
 * WebPush: poucas linhas, nenhuma dependência nova). O endpoint é configurável, o que permite testar contra um S3 falso local.
 * Nenhuma credencial é gravada: vêm de {@link S3CredentialsProvider} (variáveis de ambiente ou role da instância).
 */
public class S3BackupStorage implements BackupStorage {

    private static final DateTimeFormatter AMZ_DATE = DateTimeFormatter.ofPattern("yyyyMMdd'T'HHmmss'Z'").withZone(ZoneOffset.UTC);
    private static final DateTimeFormatter DATE = DateTimeFormatter.ofPattern("yyyyMMdd").withZone(ZoneOffset.UTC);
    private static final String EMPTY_SHA256 = "e3b0c44298fc1c149afbf4c8996fb92427ae41e4649b934ca495991b7852b855";

    private final String endpoint;
    private final String bucket;
    private final String region;
    private final S3CredentialsProvider credentials;
    private final Clock clock;
    private final HttpClient http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(10)).build();

    public S3BackupStorage(String endpoint, String region, String bucket, S3CredentialsProvider credentials, Clock clock) {
        String e = endpoint == null || endpoint.isBlank() ? "https://s3." + region + ".amazonaws.com" : endpoint.strip();
        this.endpoint = e.endsWith("/") ? e.substring(0, e.length() - 1) : e;
        this.bucket = bucket;
        this.region = region;
        this.credentials = credentials;
        this.clock = clock;
    }

    @Override
    public void put(String key, Path file) {
        try {
            String hash = sha256(file);
            HttpRequest.Builder b = request("PUT", "/" + bucket + "/" + encodePath(key), Map.of(), hash)
                    .PUT(HttpRequest.BodyPublishers.ofFile(file));
            HttpResponse<String> r = http.send(b.build(), HttpResponse.BodyHandlers.ofString());
            expect(r.statusCode(), "gravar", 200);
        } catch (IOException e) {
            throw new BackupException("falha de rede ao gravar o backup no S3", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("interrompido ao gravar o backup no S3", e);
        }
    }

    @Override
    public void get(String key, Path target) {
        try {
            HttpRequest request = request("GET", "/" + bucket + "/" + encodePath(key), Map.of(), EMPTY_SHA256).GET().build();
            HttpResponse<InputStream> r = http.send(request, HttpResponse.BodyHandlers.ofInputStream());
            try (InputStream body = r.body()) {
                expect(r.statusCode(), "baixar", 200);
                Files.copy(body, target, StandardCopyOption.REPLACE_EXISTING);
            }
        } catch (IOException e) {
            throw new BackupException("falha de rede ao baixar o backup do S3", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("interrompido ao baixar o backup do S3", e);
        }
    }

    @Override
    public List<StoredBackup> list(String prefix) {
        List<StoredBackup> all = new ArrayList<>();
        String token = null;
        try {
            do {
                Map<String, String> query = new TreeMap<>();
                query.put("list-type", "2");
                query.put("prefix", prefix);
                if (token != null) {
                    query.put("continuation-token", token);
                }
                HttpRequest request = request("GET", "/" + bucket, query, EMPTY_SHA256).GET().build();
                HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
                expect(r.statusCode(), "listar", 200);
                token = parseList(r.body(), all);
            } while (token != null);
            return all;
        } catch (IOException e) {
            throw new BackupException("falha de rede ao listar os backups do S3", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("interrompido ao listar os backups do S3", e);
        }
    }

    @Override
    public void delete(String key) {
        try {
            HttpRequest request = request("DELETE", "/" + bucket + "/" + encodePath(key), Map.of(), EMPTY_SHA256).DELETE().build();
            HttpResponse<String> r = http.send(request, HttpResponse.BodyHandlers.ofString());
            expect(r.statusCode(), "apagar", 200, 204);
        } catch (IOException e) {
            throw new BackupException("falha de rede ao apagar o backup do S3", e);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new BackupException("interrompido ao apagar o backup do S3", e);
        }
    }

    // ---- assinatura SigV4 ----

    private HttpRequest.Builder request(String method, String path, Map<String, String> query, String payloadHash) {
        Instant now = clock.instant();
        String amzDate = AMZ_DATE.format(now);
        String day = DATE.format(now);
        URI base = URI.create(endpoint);
        String host = base.getPort() == -1 ? base.getHost() : base.getHost() + ":" + base.getPort();
        S3Credentials c = credentials.credentials();

        Map<String, String> headers = new TreeMap<>();
        headers.put("host", host);
        headers.put("x-amz-content-sha256", payloadHash);
        headers.put("x-amz-date", amzDate);
        if (c.sessionToken() != null && !c.sessionToken().isBlank()) {
            headers.put("x-amz-security-token", c.sessionToken());
        }
        String canonicalQuery = canonicalQuery(query);
        String canonicalHeaders = headers.entrySet().stream().map(e -> e.getKey() + ":" + e.getValue() + "\n")
                .reduce("", String::concat);
        String signedHeaders = String.join(";", headers.keySet());
        String canonicalRequest = method + "\n" + basePath(base) + path + "\n" + canonicalQuery + "\n" + canonicalHeaders + "\n"
                + signedHeaders + "\n" + payloadHash;
        String scope = day + "/" + region + "/s3/aws4_request";
        String stringToSign = "AWS4-HMAC-SHA256\n" + amzDate + "\n" + scope + "\n" + hex(sha256(canonicalRequest.getBytes(StandardCharsets.UTF_8)));
        byte[] k = hmac(("AWS4" + c.secretKey()).getBytes(StandardCharsets.UTF_8), day);
        k = hmac(k, region);
        k = hmac(k, "s3");
        k = hmac(k, "aws4_request");
        String signature = hex(hmac(k, stringToSign));
        String authorization = "AWS4-HMAC-SHA256 Credential=" + c.accessKey() + "/" + scope + ", SignedHeaders=" + signedHeaders
                + ", Signature=" + signature;

        String url = endpoint + path + (canonicalQuery.isEmpty() ? "" : "?" + canonicalQuery);
        HttpRequest.Builder b = HttpRequest.newBuilder(URI.create(url)).timeout(Duration.ofMinutes(5))
                .header("x-amz-content-sha256", payloadHash).header("x-amz-date", amzDate).header("Authorization", authorization);
        if (headers.containsKey("x-amz-security-token")) {
            b.header("x-amz-security-token", c.sessionToken());
        }
        return b;
    }

    private static String basePath(URI base) {
        String p = base.getPath();
        return p == null || p.equals("/") ? "" : p;
    }

    private static String canonicalQuery(Map<String, String> query) {
        TreeMap<String, String> sorted = new TreeMap<>();
        query.forEach((k, v) -> sorted.put(encode(k), encode(v)));
        return sorted.entrySet().stream().map(e -> e.getKey() + "=" + e.getValue()).reduce((a, b) -> a + "&" + b).orElse("");
    }

    private static String encodePath(String key) {
        String[] parts = key.split("/", -1);
        List<String> encoded = new ArrayList<>();
        for (String part : parts) {
            encoded.add(encode(part));
        }
        return String.join("/", encoded);
    }

    private static String encode(String value) {
        return URLEncoder.encode(value, StandardCharsets.UTF_8).replace("+", "%20").replace("*", "%2A").replace("%7E", "~");
    }

    private static void expect(int status, String action, int... accepted) {
        for (int ok : accepted) {
            if (status == ok) {
                return;
            }
        }
        throw new BackupException("o S3 recusou " + action + " o backup (HTTP " + status + ")");
    }

    private static String parseList(String xml, List<StoredBackup> out) {
        try {
            DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
            f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true);
            f.setXIncludeAware(false);
            f.setExpandEntityReferences(false);
            Document doc = f.newDocumentBuilder().parse(new InputSource(new java.io.StringReader(xml)));
            NodeList contents = doc.getElementsByTagName("Contents");
            for (int i = 0; i < contents.getLength(); i++) {
                Element e = (Element) contents.item(i);
                out.add(new StoredBackup(text(e, "Key"), Instant.parse(text(e, "LastModified")), Long.parseLong(text(e, "Size"))));
            }
            boolean truncated = "true".equals(doc.getElementsByTagName("IsTruncated").getLength() == 0 ? "false"
                    : doc.getElementsByTagName("IsTruncated").item(0).getTextContent().strip());
            NodeList next = doc.getElementsByTagName("NextContinuationToken");
            return truncated && next.getLength() > 0 ? next.item(0).getTextContent().strip() : null;
        } catch (Exception e) {
            throw new BackupException("resposta de listagem do S3 inválida", e);
        }
    }

    private static String text(Element parent, String tag) {
        return parent.getElementsByTagName(tag).item(0).getTextContent().strip();
    }

    private static String sha256(Path file) throws IOException {
        try (InputStream in = Files.newInputStream(file)) {
            MessageDigest d = MessageDigest.getInstance("SHA-256");
            byte[] buf = new byte[64 * 1024];
            int n;
            while ((n = in.read(buf)) > 0) {
                d.update(buf, 0, n);
            }
            return hex(d.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] sha256(byte[] data) {
        try {
            return MessageDigest.getInstance("SHA-256").digest(data);
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException(e);
        }
    }

    private static byte[] hmac(byte[] key, String data) {
        try {
            Mac mac = Mac.getInstance("HmacSHA256");
            mac.init(new SecretKeySpec(key, "HmacSHA256"));
            return mac.doFinal(data.getBytes(StandardCharsets.UTF_8));
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    private static String hex(byte[] bytes) {
        return HexFormat.of().formatHex(bytes);
    }
}
