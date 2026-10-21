package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicBoolean;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.context.annotation.Primary;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;

@Import(QuickEntryNotificationIT.RecordingPush.class)
@DisplayName("Aviso por push do lançamento criado pelo Atalho")
class QuickEntryNotificationIT extends QuickEntryItBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPush {

        static final List<PushSender.PushMessage> SENT = new CopyOnWriteArrayList<>();
        static final AtomicBoolean FAIL = new AtomicBoolean();

        @Bean
        @Primary
        PushSender quickEntryRecordingPushSender() {
            return new PushSender() {
                @Override
                public Result send(PushSubscription subscription, PushMessage message) {
                    if (FAIL.get()) {
                        throw new IllegalStateException("push fora do ar");
                    }
                    SENT.add(message);
                    return Result.SENT;
                }

                @Override
                public boolean enabled() {
                    return true;
                }

                @Override
                public String publicKey() {
                    return "test";
                }
            };
        }
    }

    @Autowired
    private PushSubscriptionGateway subscriptions;

    @AfterEach
    void limpar() {
        RecordingPush.SENT.clear();
        RecordingPush.FAIL.set(false);
        committed(() -> jdbc.update("delete from push_subscriptions"));
    }

    private String secret;

    private World subscribed() throws Exception {
        World w = world();
        committed(() -> subscriptions.upsert(new PushSubscription(UUID.randomUUID(), w.userId(), "https://push.example/" + w.userId(), "p", "a", "ua",
                Instant.now())));
        QuickEntryTokenSecret token = QuickEntryTokenSecret.generate();
        Instant now = clock.instant();
        committed(() -> tokens.insert(QuickEntryToken.issue(UUID.randomUUID(), w.userId(), w.accountId(), "Toque", token.prefix(), token.hash(), now,
                now.plusSeconds(3600))));
        secret = token.value();
        return w;
    }

    private MvcResult send(String key, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/v1/quick-entries").header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private void waitFor(int count) throws InterruptedException {
        long deadline = System.currentTimeMillis() + 5_000;
        while (RecordingPush.SENT.size() < count && System.currentTimeMillis() < deadline) {
            Thread.sleep(25);
        }
        Thread.sleep(300);
    }

    @Test
    @DisplayName("criou: avisa uma vez com tipo, valor, descrição e id; replay da mesma chave não avisa de novo; o payload não leva o token")
    void deveAvisarSoNaCriacao() throws Exception {
        subscribed();
        String key = "chave" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
        String json = "{\"kind\":\"EXPENSE\",\"amount\":\"54,60\",\"description\":\"Mercado\"}";

        MvcResult first = send(key, json);
        waitFor(1);
        MvcResult replay = send(key, json);
        waitFor(1);

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        assertThat(RecordingPush.SENT).hasSize(1);
        PushSender.PushMessage message = RecordingPush.SENT.get(0);
        JsonNode created = objectMapper.readTree(first.getResponse().getContentAsString());
        assertThat(message.kind()).isEqualTo("SECURITY_ALERT");
        assertThat(message.titleKey()).isEqualTo("security.quick_entry_created");
        assertThat(message.params()).containsOnly(java.util.Map.entry("type", "EXPENSE"), java.util.Map.entry("amount", "54.60"),
                java.util.Map.entry("description", "Mercado"), java.util.Map.entry("transactionId", created.get("id").asString()));
        assertThat(message.params().toString()).doesNotContain(secret).doesNotContain("mlq_");
    }

    @Test
    @DisplayName("entrada avisa como INCOME; dois lançamentos seguidos avisam os dois (sem throttle)")
    void deveAvisarCadaLancamento() throws Exception {
        subscribed();

        send("a1-" + UUID.randomUUID(), "{\"kind\":\"INCOME\",\"amount\":\"100\"}");
        send("b2-" + UUID.randomUUID(), "{\"kind\":\"EXPENSE\",\"amount\":\"10\"}");
        waitFor(2);

        assertThat(RecordingPush.SENT).hasSize(2);
        assertThat(RecordingPush.SENT).extracting(m -> m.params().get("type")).containsExactlyInAnyOrder("INCOME", "EXPENSE");
    }

    @Test
    @DisplayName("falha ao enviar o push não derruba nem desfaz o lançamento")
    void deveManterOLancamentoSeOPushFalhar() throws Exception {
        World w = subscribed();
        RecordingPush.FAIL.set(true);

        MvcResult result = send("falha-" + UUID.randomUUID(), "{\"kind\":\"EXPENSE\",\"amount\":\"20\"}");
        Thread.sleep(500);

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("transactions", w.userId())).isEqualTo(1);
        assertThat(RecordingPush.SENT).isEmpty();
    }

    @Test
    @DisplayName("lançamento recusado (422) não avisa")
    void naoDeveAvisarQuandoRecusa() throws Exception {
        subscribed();

        MvcResult result = send("ruim-" + UUID.randomUUID(), "{\"kind\":\"EXPENSE\",\"amount\":\"abc\"}");
        Thread.sleep(500);

        assertThat(result.getResponse().getStatus()).isGreaterThanOrEqualTo(400);
        assertThat(RecordingPush.SENT).isEmpty();
    }
}
