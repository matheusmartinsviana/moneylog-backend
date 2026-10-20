package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.lang.reflect.InvocationTargetException;
import java.lang.reflect.Proxy;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.config.BeanPostProcessor;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import({MutableClockConfig.class, InvoiceLockOrderConcurrencyIT.GateConfig.class})
@DisplayName("Corrida determinística: mudança de dias do cartão parada antes do commit x operação sobre a fatura")
class InvoiceLockOrderConcurrencyIT {

    private static final Instant OCT_30 = Instant.parse("2026-10-30T15:00:00Z");
    private static final LocalDate NEAR_CLOSING = LocalDate.of(2026, 10, 25);
    private static final LocalDate FAR_CLOSING = LocalDate.of(2028, 6, 25);
    private static final String WRITER = "gate-writer";

    static final class Gate {
        volatile CountDownLatch arrived = new CountDownLatch(1);
        volatile CountDownLatch release = new CountDownLatch(1);

        void reset() {
            arrived = new CountDownLatch(1);
            release = new CountDownLatch(1);
        }

        void pauseIfWriter() throws InterruptedException {
            if (Thread.currentThread().getName().startsWith(WRITER)) {
                arrived.countDown();
                release.await(60, TimeUnit.SECONDS);
            }
        }
    }

    @TestConfiguration(proxyBeanMethods = false)
    static class GateConfig {

        @Bean
        Gate gate() {
            return new Gate();
        }

        @Bean
        static BeanPostProcessor cardGatewayGate(ObjectProvider<Gate> gate) {
            return new BeanPostProcessor() {
                @Override
                public Object postProcessAfterInitialization(Object bean, String beanName) {
                    if (!(bean instanceof CardGateway target)) {
                        return bean;
                    }
                    return Proxy.newProxyInstance(bean.getClass().getClassLoader(), new Class<?>[] {CardGateway.class}, (proxy, method, args) -> {
                        Object result;
                        try {
                            result = method.invoke(target, args);
                        } catch (InvocationTargetException e) {
                            throw e.getCause();
                        }
                        if (method.getName().equals("refreshInvoiceRows")) {
                            gate.getObject().pauseIfWriter();
                        }
                        return result;
                    });
                }
            };
        }
    }

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;
    @Autowired
    private MutableClock clock;
    @Autowired
    private Gate gate;

    private CardQaWorld.Actor a;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        gate.reset();
        CardQaWorld world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("lock");
        CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook"));
        CardQaWorld.ok(a.purchase("1083.00", 1, "2026-10-10", "Mercado"));
        a.at(OCT_30);
    }

    private long paymentCount() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM transactions WHERE user_id = ? AND type = 'INVOICE_PAYMENT'", Long.class, a.id);
        return n == null ? 0 : n;
    }

    private long blockedOnLock() {
        Long n = jdbc.queryForObject("SELECT count(*) FROM pg_stat_activity WHERE wait_event_type = 'Lock' AND datname = current_database()",
                Long.class);
        return n == null ? 0 : n;
    }

    private void awaitBlocked(Future<?> follower) throws Exception {
        for (int i = 0; i < 200 && blockedOnLock() < 1; i++) {
            Thread.sleep(50);
        }
        assertThat(blockedOnLock()).as("a segunda operação deveria estar esperando a trava do cartão").isGreaterThanOrEqualTo(1);
        try {
            follower.get(500, TimeUnit.MILLISECONDS);
            throw new AssertionError("a segunda operação terminou antes de a mudança de dias confirmar");
        } catch (TimeoutException expected) {
            assertThat(follower.isDone()).isFalse();
        }
    }

    private UUID target(boolean materialized) {
        UUID invoice = a.invoiceId(materialized ? NEAR_CLOSING : FAR_CLOSING);
        Long rows = jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE id = ?", Long.class, invoice);
        assertThat(rows).isEqualTo(materialized ? 1L : 0L);
        return invoice;
    }

    private void race(Callable<CardQaWorld.Res> follower, boolean paying, int expected) throws Exception {
        JsonNode card0 = a.cardNow();
        String confirmed = a.getOk("/v1/cards/" + a.card + "/billing-change-preview?closingDay=10&dueDay=5").get("effectiveFromClosingDate").asString();
        ExecutorService writers = Executors.newSingleThreadExecutor(r -> new Thread(r, WRITER));
        ExecutorService followers = Executors.newSingleThreadExecutor();
        try {
            Future<CardQaWorld.Res> change = writers.submit(() -> a.putDays(a.card, 10, 5, confirmed, card0.get("version").asInt()));
            assertThat(gate.arrived.await(30, TimeUnit.SECONDS)).as("a mudança de dias chegou ao ponto anterior ao commit").isTrue();
            Future<CardQaWorld.Res> second = followers.submit(follower);
            try {
                awaitBlocked(second);
            } finally {
                gate.release.countDown();
            }
            CardQaWorld.Res changed = change.get(60, TimeUnit.SECONDS);
            CardQaWorld.Res result = second.get(60, TimeUnit.SECONDS);
            assertThat(changed.status()).as(changed.toString()).isEqualTo(200);
            assertThat(result.status()).as(result.toString()).isIn(200, 201, 404, 409).isEqualTo(expected);
            assertThat(a.ledgerSum()).isZero();
            assertThat(CardInvariants.check(a, a.card)).isEmpty();
            assertThat(paymentCount()).isEqualTo(paying && result.status() == 200 ? 1 : 0);
        } finally {
            gate.release.countDown();
            writers.shutdownNow();
            followers.shutdownNow();
        }
    }

    @Test
    @DisplayName("pagar fatura materializada enquanto a mudança de dias segura a trava do cartão")
    void pagarMaterializada() throws Exception {
        UUID invoice = target(true);
        race(() -> a.pay(invoice, "1283.00", "2026-10-30", null), true, 200);
    }

    @Test
    @DisplayName("pagar fatura sem linha materializada enquanto a mudança de dias segura a trava do cartão")
    void pagarNaoMaterializada() throws Exception {
        UUID invoice = target(false);
        race(() -> a.pay(invoice, "100.00", "2026-10-30", null), true, 404);
    }

    @Test
    @DisplayName("alterar valor do documento da fatura materializada enquanto a mudança de dias segura a trava")
    void documentoMaterializada() throws Exception {
        UUID invoice = target(true);
        race(() -> documentAmount(invoice), false, 200);
    }

    @Test
    @DisplayName("alterar valor do documento da fatura sem linha materializada enquanto a mudança de dias segura a trava")
    void documentoNaoMaterializada() throws Exception {
        UUID invoice = target(false);
        race(() -> documentAmount(invoice), false, 404);
    }

    private CardQaWorld.Res documentAmount(UUID invoice) throws Exception {
        return a.call(HttpMethod.PUT, "/v1/invoices/" + invoice + "/document-amount", "{\"documentAmount\":\"1283.00\"}", null);
    }
}
