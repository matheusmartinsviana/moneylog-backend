package com.moneylog.infra.notification.push;

import com.moneylog.core.notification.domain.PushEndpointPolicy;
import java.io.IOException;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.nio.charset.StandardCharsets;
import java.security.GeneralSecurityException;
import java.security.PrivateKey;
import java.time.Clock;
import java.time.Duration;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.function.Predicate;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;

/**
 * Web Push com VAPID (S-173, T130): mensagem cifrada ({@code aes128gcm}) enviada ao serviço de push do navegador, com {@code TTL} e {@code Urgency}.
 * {@code 404/410} = assinatura morta ({@link Result#GONE}); {@code 429/5xx} = recuo ({@link Result#RETRY_LATER}). O corpo da resposta, o endpoint e
 * as chaves nunca vão para o log. <b>Não foi exercitado em dispositivo</b>: depende de HTTPS real, chaves VAPID e PWA instalada.
 */
public class WebPushSender implements PushSender {

    private static final Logger log = LoggerFactory.getLogger(WebPushSender.class);

    private final String publicKey;
    private final PrivateKey privateKey;
    private final String subject;
    private final ObjectMapper mapper;
    private final HttpClient http;
    private final Clock clock;
    private final int ttlSeconds;
    private final Predicate<String> endpointPolicy;

    public WebPushSender(String publicKey, String privateKey, String subject, ObjectMapper mapper, Clock clock, int ttlSeconds) {
        this(publicKey, privateKey, subject, mapper, clock, ttlSeconds, PushEndpointPolicy::isAllowed);
    }

    /** A política de endpoint é injetável só para o teste contra servidor local; em produção é sempre {@link PushEndpointPolicy}. */
    WebPushSender(String publicKey, String privateKey, String subject, ObjectMapper mapper, Clock clock, int ttlSeconds,
                  Predicate<String> endpointPolicy) {
        this.endpointPolicy = endpointPolicy;
        try {
            this.privateKey = WebPushCrypto.privateKey(WebPushCrypto.decode(privateKey));
            WebPushCrypto.publicKey(WebPushCrypto.decode(publicKey));
        } catch (GeneralSecurityException | IllegalArgumentException e) {
            throw new IllegalStateException("chaves VAPID inválidas (MONEYLOG_PUSH_VAPID_PUBLIC/PRIVATE em base64url, P-256)", e);
        }
        this.publicKey = publicKey;
        this.subject = subject;
        this.mapper = mapper;
        this.clock = clock;
        this.ttlSeconds = ttlSeconds;
        this.http = HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(5)).followRedirects(HttpClient.Redirect.NEVER).build();
    }

    @Override
    public Result send(PushSubscription subscription, PushMessage message) {
        try {
            if (!endpointPolicy.test(subscription.endpoint())) {
                log.warn("Endpoint de push fora da lista permitida; assinatura ignorada");
                return Result.GONE;
            }
            URI endpoint = URI.create(subscription.endpoint());
            String audience = endpoint.getScheme() + "://" + endpoint.getRawAuthority();
            String jwt = WebPushCrypto.vapidJwt(audience, clock.instant().plusSeconds(12 * 3600L).getEpochSecond(), subject, privateKey);
            byte[] body = WebPushCrypto.encrypt(payload(message), WebPushCrypto.decode(subscription.p256dh()), WebPushCrypto.decode(subscription.auth()));
            HttpRequest request = HttpRequest.newBuilder(endpoint).timeout(Duration.ofSeconds(10))
                    .header("Content-Type", "application/octet-stream").header("Content-Encoding", "aes128gcm")
                    .header("TTL", Integer.toString(ttlSeconds)).header("Urgency", "normal")
                    .header("Authorization", "vapid t=" + jwt + ", k=" + publicKey)
                    .POST(HttpRequest.BodyPublishers.ofByteArray(body)).build();
            int status = http.send(request, HttpResponse.BodyHandlers.discarding()).statusCode();
            if (status >= 200 && status < 300) {
                return Result.SENT;
            }
            if (status == 404 || status == 410) {
                return Result.GONE;
            }
            if (status == 429 || status >= 500) {
                return Result.RETRY_LATER;
            }
            log.warn("Serviço de push recusou a mensagem (status {})", status);
            return Result.FAILED;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return Result.RETRY_LATER;
        } catch (IOException e) {
            log.warn("Falha de rede ao enviar push ({})", e.getClass().getSimpleName());
            return Result.RETRY_LATER;
        } catch (GeneralSecurityException | IllegalArgumentException | JacksonException e) {
            log.warn("Não foi possível montar o push ({})", e.getClass().getSimpleName());
            return Result.FAILED;
        }
    }

    private byte[] payload(PushMessage message) {
        Map<String, Object> json = new LinkedHashMap<>();
        json.put("id", message.notificationId().toString());
        json.put("kind", message.kind());
        json.put("titleKey", message.titleKey());
        json.put("params", message.params());
        return mapper.writeValueAsString(json).getBytes(StandardCharsets.UTF_8);
    }

    @Override
    public boolean enabled() {
        return true;
    }

    @Override
    public String publicKey() {
        return publicKey;
    }
}
