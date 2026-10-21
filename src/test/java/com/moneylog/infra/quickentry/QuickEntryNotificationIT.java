package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;
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
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.notification.app.gateways.PushSender;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway;
import com.moneylog.core.notification.app.gateways.PushSubscriptionGateway.PushSubscription;
import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;

@Import(QuickEntryNotificationIT.RecordingPush.class)
@TestPropertySource(properties = "moneylog.quick-entry.max-entries-per-day=2")
@DisplayName("Aviso por push do lançamento criado pelo Atalho")
class QuickEntryNotificationIT extends QuickEntryItBase {

    @TestConfiguration(proxyBeanMethods = false)
    static class RecordingPush {

        static final List<PushSender.PushMessage> SENT = new CopyOnWriteArrayList<>();
        static final AtomicBoolean FAIL = new AtomicBoolean();
        static final java.util.concurrent.atomic.AtomicInteger FAILED = new java.util.concurrent.atomic.AtomicInteger();

        @Bean
        @Primary
        PushSender quickEntryRecordingPushSender() {
            return new PushSender() {
                @Override
                public Result send(PushSubscription subscription, PushMessage message) {
                    if (FAIL.get()) {
                        FAILED.incrementAndGet();
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
        RecordingPush.FAILED.set(0);
        committed(() -> jdbc.update("delete from push_subscriptions"));
    }

    private record Actor(World world, String secret) {
    }

    private Actor actor(boolean pushOn) throws Exception {
        World w = world();
        committed(() -> {
            subscriptions.upsert(new PushSubscription(UUID.randomUUID(), w.userId(), "https://push.example/" + w.userId(), "p", "a", "ua",
                    Instant.now()));
            jdbc.update("insert into notification_settings (user_id, push_enabled, version, updated_at) values (?, ?, 0, now()) "
                    + "on conflict (user_id) do update set push_enabled = excluded.push_enabled", w.userId(), pushOn);
        });
        QuickEntryTokenSecret token = QuickEntryTokenSecret.generate();
        Instant now = clock.instant();
        committed(() -> tokens.insert(QuickEntryToken.issue(UUID.randomUUID(), w.userId(), w.accountId(), "Toque", token.prefix(), token.hash(), now,
                now.plusSeconds(3600))));
        return new Actor(w, token.value());
    }

    private MvcResult send(String secret, String key, String json) throws Exception {
        return mockMvc.perform(MockMvcRequestBuilders.post("/v1/quick-entries").header(HttpHeaders.AUTHORIZATION, "Bearer " + secret)
                .header("Idempotency-Key", key).contentType(MediaType.APPLICATION_JSON).content(json)).andReturn();
    }

    private static String key() {
        return "chave" + UUID.randomUUID().toString().replace("-", "").substring(0, 16);
    }

    private static void await(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(5);
        while (!condition.getAsBoolean() && System.nanoTime() < deadline) {
            Thread.sleep(10);
        }
        assertThat(condition.getAsBoolean()).isTrue();
    }

    private long sentFor(UUID transactionId) {
        return RecordingPush.SENT.stream().filter(m -> transactionId.toString().equals(m.params().get("transactionId"))).count();
    }

    private void drainWithControl() throws Exception {
        Actor control = actor(true);
        MvcResult result = send(control.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\"}");
        UUID id = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString());
        await(() -> sentFor(id) == 1);
    }

    @Test
    @DisplayName("criou: avisa uma vez com tipo, valor, descrição e id; replay da mesma chave não avisa de novo; o payload não leva o token")
    void deveAvisarSoNaCriacao() throws Exception {
        Actor a = actor(true);
        String key = key();
        String json = "{\"kind\":\"EXPENSE\",\"amount\":\"54,60\",\"description\":\"Mercado\"}";

        MvcResult first = send(a.secret(), key, json);
        MvcResult replay = send(a.secret(), key, json);
        drainWithControl();

        assertThat(first.getResponse().getStatus()).isEqualTo(201);
        assertThat(replay.getResponse().getStatus()).isEqualTo(201);
        JsonNode created = objectMapper.readTree(first.getResponse().getContentAsString());
        UUID id = UUID.fromString(created.get("id").asString());
        assertThat(sentFor(id)).isEqualTo(1);
        PushSender.PushMessage message = RecordingPush.SENT.stream().filter(m -> id.toString().equals(m.params().get("transactionId"))).findFirst()
                .orElseThrow();
        assertThat(message.kind()).isEqualTo("SECURITY_ALERT");
        assertThat(message.titleKey()).isEqualTo("security.quick_entry_created");
        assertThat(message.params()).containsOnly(Map.entry("type", "EXPENSE"), Map.entry("amount", "54.60"), Map.entry("description", "Mercado"),
                Map.entry("transactionId", id.toString()));
        assertThat(message.params().toString()).doesNotContain(a.secret()).doesNotContain("mlq_");
    }

    @Test
    @DisplayName("requisições concorrentes com a mesma Idempotency-Key geram um só lançamento e um só aviso")
    void deveAvisarUmaVezSobConcorrencia() throws Exception {
        Actor a = actor(true);
        String key = key();
        String json = "{\"kind\":\"EXPENSE\",\"amount\":\"33\"}";
        int threads = 6;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch start = new CountDownLatch(1);
        java.util.List<Future<MvcResult>> futures = new java.util.ArrayList<>();
        for (int i = 0; i < threads; i++) {
            futures.add(pool.submit(() -> {
                start.await();
                return send(a.secret(), key, json);
            }));
        }
        start.countDown();
        UUID id = null;
        for (Future<MvcResult> future : futures) {
            MvcResult result = future.get(30, TimeUnit.SECONDS);
            assertThat(result.getResponse().getStatus()).isEqualTo(201);
            id = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString());
        }
        pool.shutdown();
        drainWithControl();

        assertThat(count("transactions", a.world().userId())).isEqualTo(1);
        assertThat(sentFor(id)).isEqualTo(1);
    }

    @Test
    @DisplayName("entrada avisa como INCOME; dois lançamentos seguidos avisam os dois (sem throttle)")
    void deveAvisarCadaLancamento() throws Exception {
        Actor a = actor(true);

        MvcResult income = send(a.secret(), key(), "{\"kind\":\"INCOME\",\"amount\":\"100\"}");
        MvcResult expense = send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"10\"}");
        UUID incomeId = UUID.fromString(objectMapper.readTree(income.getResponse().getContentAsString()).get("id").asString());
        UUID expenseId = UUID.fromString(objectMapper.readTree(expense.getResponse().getContentAsString()).get("id").asString());
        await(() -> sentFor(incomeId) == 1 && sentFor(expenseId) == 1);

        assertThat(RecordingPush.SENT.stream().filter(m -> incomeId.toString().equals(m.params().get("transactionId"))).findFirst().orElseThrow()
                .params()).containsEntry("type", "INCOME");
    }

    @Test
    @DisplayName("falha ao enviar o push não derruba nem desfaz o lançamento")
    void deveManterOLancamentoSeOPushFalhar() throws Exception {
        Actor a = actor(true);
        RecordingPush.FAIL.set(true);

        MvcResult result = send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"20\"}");
        await(() -> RecordingPush.FAILED.get() == 1);
        RecordingPush.FAIL.set(false);
        drainWithControl();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(count("transactions", a.world().userId())).isEqualTo(1);
        assertThat(RecordingPush.SENT).hasSize(1);
    }

    @Test
    @DisplayName("push desligado pelo usuário: o lançamento é criado e nada é enviado")
    void naoDeveAvisarComPushDesligado() throws Exception {
        Actor a = actor(false);

        MvcResult result = send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"20\"}");
        UUID id = UUID.fromString(objectMapper.readTree(result.getResponse().getContentAsString()).get("id").asString());
        drainWithControl();

        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        assertThat(sentFor(id)).isZero();
    }

    @Test
    @DisplayName("422 de valor, 422 de categoria, 422 de limite diário e 401 de token: nenhum avisa")
    void naoDeveAvisarQuandoRecusa() throws Exception {
        Actor a = actor(true);

        assertThat(send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"0\"}").getResponse().getStatus()).isEqualTo(422);
        assertThat(send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"5\",\"category\":\"inexistente-xyz\"}").getResponse().getStatus())
                .isEqualTo(422);
        assertThat(send("mlq_invalido", key(), "{\"kind\":\"EXPENSE\",\"amount\":\"5\"}").getResponse().getStatus()).isEqualTo(401);
        assertThat(send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"1\"}").getResponse().getStatus()).isEqualTo(201);
        assertThat(send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"2\"}").getResponse().getStatus()).isEqualTo(201);
        MvcResult limited = send(a.secret(), key(), "{\"kind\":\"EXPENSE\",\"amount\":\"3\"}");
        drainWithControl();

        assertThat(limited.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(limited.getResponse().getContentAsString()).get("code").asString()).isEqualTo("QUICK_LIMIT_REACHED");
        assertThat(count("transactions", a.world().userId())).isEqualTo(2);
        assertThat(RecordingPush.SENT.stream().filter(m -> "3.00".equals(m.params().get("amount")))).isEmpty();
        assertThat(RecordingPush.SENT.stream().filter(m -> "5.00".equals(m.params().get("amount")))).isEmpty();
    }
}
