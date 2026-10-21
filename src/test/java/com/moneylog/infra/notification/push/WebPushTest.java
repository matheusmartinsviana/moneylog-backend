package com.moneylog.infra.notification.push;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.PrivateKey;
import java.security.Signature;
import java.security.spec.ECGenParameterSpec;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneOffset;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.sun.net.httpserver.HttpServer;

import tools.jackson.databind.json.JsonMapper;

import com.moneylog.core.notification.app.gateways.PushSender.PushMessage;
import com.moneylog.core.notification.app.gateways.PushSender.Result;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;

@DisplayName("Web Push preparado e desligado (T130): cifra, VAPID e envio contra servidor falso")
class WebPushTest {

    private HttpServer server;

    @AfterEach
    void stop() {
        if (server != null) {
            server.stop(0);
        }
    }

    private static KeyPair p256() throws Exception {
        KeyPairGenerator generator = KeyPairGenerator.getInstance("EC");
        generator.initialize(new ECGenParameterSpec("secp256r1"));
        return generator.generateKeyPair();
    }

    private static byte[] scalar(PrivateKey key) {
        byte[] raw = ((java.security.interfaces.ECPrivateKey) key).getS().toByteArray();
        byte[] out = new byte[32];
        int start = raw[0] == 0 ? 1 : 0;
        System.arraycopy(raw, start, out, 32 - (raw.length - start), raw.length - start);
        return out;
    }

    @Test
    @DisplayName("vetor da RFC 8291 §5: o corpo aes128gcm sai idêntico ao publicado")
    void vetorDaRfc() throws Exception {
        byte[] uaPublic = WebPushCrypto.decode("BCVxsr7N_eNgVRqvHtD0zTZsEc6-VV-JvLexhqUzORcxaOzi6-AYWXvTBHm4bjyPjs7Vd8pZGH6SRpkNtoIAiw4");
        byte[] auth = WebPushCrypto.decode("BTBZMqHH6r4Tts7J_aSIgg");
        PrivateKey asPrivate = WebPushCrypto.privateKey(WebPushCrypto.decode("yfWPiYE-n46HLnH0KqZOF1fJJU3MYrct3AELtAQ-oRw"));
        byte[] asPublic = WebPushCrypto.decode("BP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEqKK6PBru3jl7A8");
        byte[] salt = WebPushCrypto.decode("DGv6ra1nlYgDCS1FRnbzlw");
        byte[] body = WebPushCrypto.encrypt("When I grow up, I want to be a watermelon".getBytes(StandardCharsets.UTF_8), uaPublic, auth, asPrivate, asPublic,
                salt);
        assertThat(WebPushCrypto.encode(body)).isEqualTo("DGv6ra1nlYgDCS1FRnbzlwAAEABBBP4z9KsN6nGRTbVYI_c7VJSPQTBtkgcy27mlmlMoZIIgDll6e3vCYLocInmYWAmS6TlzAC8wEq"
                + "KK6PBru3jl7A_yl95bQpu6cVPTpK4Mqgkf1CXztLVBSt2Ks3oZwbuwXPXLWyouBWLVWGNWQexSgSxsj_Qulcy4a-fN");
    }

    @Test
    @DisplayName("ida e volta: o receptor decifra o que o remetente cifrou, com chaves e sal novos a cada mensagem")
    void idaEVolta() throws Exception {
        KeyPair receiver = p256();
        byte[] receiverPublic = WebPushCrypto.uncompressed(receiver.getPublic());
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        byte[] first = WebPushCrypto.encrypt("olá, ação".getBytes(StandardCharsets.UTF_8), receiverPublic, auth);
        byte[] second = WebPushCrypto.encrypt("olá, ação".getBytes(StandardCharsets.UTF_8), receiverPublic, auth);
        assertThat(first).isNotEqualTo(second);
        assertThat(new String(WebPushCrypto.decrypt(first, receiver.getPrivate(), receiverPublic, auth), StandardCharsets.UTF_8)).isEqualTo("olá, ação");
        byte[] tampered = first.clone();
        tampered[tampered.length - 1] ^= 1;
        assertThatThrownBy(() -> WebPushCrypto.decrypt(tampered, receiver.getPrivate(), receiverPublic, auth)).isInstanceOf(java.security.GeneralSecurityException.class);
    }

    @Test
    @DisplayName("JWT VAPID: ES256 válido com aud, exp e sub")
    void jwt() throws Exception {
        KeyPair vapid = p256();
        String jwt = WebPushCrypto.vapidJwt("https://push.example", 1_800_000_000L, "mailto:eu@example.com", vapid.getPrivate());
        String[] parts = jwt.split("\\.");
        assertThat(new String(Base64.getUrlDecoder().decode(parts[1]), StandardCharsets.UTF_8))
                .isEqualTo("{\"aud\":\"https://push.example\",\"exp\":1800000000,\"sub\":\"mailto:eu@example.com\"}");
        Signature verify = Signature.getInstance("SHA256withECDSAinP1363Format");
        verify.initVerify(vapid.getPublic());
        verify.update((parts[0] + "." + parts[1]).getBytes(StandardCharsets.US_ASCII));
        assertThat(verify.verify(Base64.getUrlDecoder().decode(parts[2]))).isTrue();
    }

    private WebPushSender sender() throws Exception {
        KeyPair vapid = p256();
        return new WebPushSender(WebPushCrypto.encode(WebPushCrypto.uncompressed(vapid.getPublic())), WebPushCrypto.encode(scalar(vapid.getPrivate())),
                "mailto:eu@example.com", JsonMapper.builder().build(), Clock.fixed(Instant.parse("2026-11-02T11:30:00Z"), ZoneOffset.UTC), 3600,
                endpoint -> true);
    }

    private PushSubscription subscription(String endpoint, KeyPair receiver, byte[] auth) {
        return new PushSubscription(UUID.randomUUID(), UUID.randomUUID(), endpoint, WebPushCrypto.encode(WebPushCrypto.uncompressed(receiver.getPublic())),
                WebPushCrypto.encode(auth), null, Instant.now());
    }

    @Test
    @DisplayName("servidor falso: envia POST cifrado com TTL, Urgency, Content-Encoding e VAPID; payload decifrado só traz id, tipo, chave e parâmetros")
    void envia() throws Exception {
        KeyPair receiver = p256();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        AtomicReference<byte[]> body = new AtomicReference<>();
        AtomicReference<com.sun.net.httpserver.Headers> headers = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            headers.set(exchange.getRequestHeaders());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
        UUID id = UUID.randomUUID();

        Result result = sender().send(subscription(endpoint, receiver, auth), new PushMessage(id, "INVOICE_DUE", "notification.invoice.due3",
                Map.of("name", "Roxinho", "dueDate", "2026-11-05")));

        assertThat(result).isEqualTo(Result.SENT);
        assertThat(headers.get().getFirst("Content-Encoding")).isEqualTo("aes128gcm");
        assertThat(headers.get().getFirst("TTL")).isEqualTo("3600");
        assertThat(headers.get().getFirst("Urgency")).isEqualTo("normal");
        assertThat(headers.get().getFirst("Authorization")).startsWith("vapid t=").contains(", k=");
        String payload = new String(WebPushCrypto.decrypt(body.get(), receiver.getPrivate(), WebPushCrypto.uncompressed(receiver.getPublic()), auth),
                StandardCharsets.UTF_8);
        assertThat(payload).contains("\"id\":\"" + id + "\"").contains("\"kind\":\"INVOICE_DUE\"").contains("\"titleKey\":\"notification.invoice.due3\"")
                .contains("\"name\":\"Roxinho\"").doesNotContain("amount");
    }

    @Test
    @DisplayName("aviso de lançamento do Atalho: payload com acento, emoji, valor e id da movimentação é cifrado, enviado e decifrado inteiro")
    void enviaAvisoDeLancamentoRapido() throws Exception {
        KeyPair receiver = p256();
        byte[] auth = new byte[16];
        new java.security.SecureRandom().nextBytes(auth);
        AtomicReference<byte[]> body = new AtomicReference<>();
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            body.set(exchange.getRequestBody().readAllBytes());
            exchange.sendResponseHeaders(201, -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
        UUID transactionId = UUID.randomUUID();
        Map<String, String> params = Map.of("type", "EXPENSE", "amount", "54.60", "description", "Mercado São João \uD83D\uDED2" + "x".repeat(60),
                "transactionId", transactionId.toString());

        Result result = sender().send(subscription(endpoint, receiver, auth),
                new PushMessage(UUID.randomUUID(), "SECURITY_ALERT", "security.quick_entry_created", params));

        assertThat(result).isEqualTo(Result.SENT);
        assertThat(body.get().length).isLessThan(4096);
        String payload = new String(WebPushCrypto.decrypt(body.get(), receiver.getPrivate(), WebPushCrypto.uncompressed(receiver.getPublic()), auth),
                StandardCharsets.UTF_8);
        assertThat(payload).contains("\"kind\":\"SECURITY_ALERT\"").contains("\"titleKey\":\"security.quick_entry_created\"")
                .contains("\"amount\":\"54.60\"").contains("\"type\":\"EXPENSE\"").contains("\"transactionId\":\"" + transactionId + "\"")
                .contains("Mercado São João \uD83D\uDED2");
    }

    @Test
    @DisplayName("404 e 410 = assinatura morta (GONE); 429 e 5xx = recuo (RETRY_LATER); 400 = FAILED; servidor fora do ar = recuo")
    void status() throws Exception {
        KeyPair receiver = p256();
        byte[] auth = new byte[16];
        AtomicInteger status = new AtomicInteger(201);
        server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        server.createContext("/push", exchange -> {
            exchange.getRequestBody().readAllBytes();
            exchange.sendResponseHeaders(status.get(), -1);
            exchange.close();
        });
        server.start();
        String endpoint = "http://127.0.0.1:" + server.getAddress().getPort() + "/push";
        WebPushSender sender = sender();
        PushMessage message = new PushMessage(UUID.randomUUID(), "REMINDER", "notification.reminder", Map.of());
        for (Object[] row : new Object[][] {{404, Result.GONE}, {410, Result.GONE}, {429, Result.RETRY_LATER}, {503, Result.RETRY_LATER},
                {400, Result.FAILED}, {201, Result.SENT}}) {
            status.set((Integer) row[0]);
            assertThat(sender.send(subscription(endpoint, receiver, auth), message)).as("status " + row[0]).isEqualTo(row[1]);
        }
        server.stop(0);
        server = null;
        assertThat(sender.send(subscription(endpoint, receiver, auth), message)).isEqualTo(Result.RETRY_LATER);
    }

    @Test
    @DisplayName("chaves VAPID inválidas: não constrói (o app não sobe); o NoopPushSender não envia nada")
    void chavesInvalidas() throws Exception {
        assertThatThrownBy(() -> new WebPushSender("lixo", "lixo", "mailto:x@y.z", JsonMapper.builder().build(), Clock.systemUTC(), 60))
                .isInstanceOf(IllegalStateException.class).hasMessageContaining("VAPID");
        NoopPushSender noop = new NoopPushSender();
        assertThat(noop.enabled()).isFalse();
        assertThat(noop.publicKey()).isNull();
        assertThat(noop.send(null, null)).isEqualTo(Result.FAILED);
    }

    @SuppressWarnings("unused")
    private void io() throws IOException {
    }
}
