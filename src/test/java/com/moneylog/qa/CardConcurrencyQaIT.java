package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.sql.Connection;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CyclicBarrier;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import javax.sql.DataSource;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T61 (QA), critério 3: concorrência no cartão. Ordem de travas do plano: movimentações → cartão → contas por id.
 * Nenhuma corrida pode dar 5xx, fatura duplicada, outstanding negativo ou violar o invariante do cartão.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T61: concorrência no cartão")
class CardConcurrencyQaIT {

    private static final Instant OCT_30 = Instant.parse("2026-10-30T15:00:00Z");

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
    private DataSource dataSource;

    private CardQaWorld world;
    private CardQaWorld.Actor a;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = world.new Actor("cc");
    }

    private <T> List<T> race(List<Callable<T>> tasks) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            CyclicBarrier barrier = new CyclicBarrier(tasks.size());
            List<Future<T>> futures = new ArrayList<>();
            for (Callable<T> t : tasks) {
                futures.add(pool.submit(() -> {
                    barrier.await();
                    return t.call();
                }));
            }
            List<T> out = new ArrayList<>();
            for (Future<T> f : futures) {
                out.add(f.get());
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private static Map<Integer, Integer> histogram(List<CardQaWorld.Res> results) {
        Map<Integer, Integer> h = new TreeMap<>();
        results.forEach(r -> h.merge(r.status(), 1, Integer::sum));
        return h;
    }

    private void assertNoServerError(String label, List<CardQaWorld.Res> results) {
        assertThat(results.stream().filter(r -> r.status() >= 500).map(CardQaWorld.Res::toString).toList()).as(label).isEmpty();
    }

    private void assertHealthy(String cardId) throws Exception {
        assertThat(CardInvariants.check(a, cardId)).isEmpty();
        assertThat(a.cardNow().get("id").asString()).isEqualTo(a.card);
        assertThat(world.contractViolations).isEmpty();
    }

    /** Linhas de fatura sem nenhuma referência e que não são FUTURE seriam lixo; todas as linhas devem estar no calendário do cartão. */
    private long orphanRows(String cardId) {
        Long n = jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices i WHERE card_id = ?::uuid AND NOT EXISTS (SELECT 1 FROM transaction_installments t "
                + "WHERE t.invoice_id = i.id) AND NOT EXISTS (SELECT 1 FROM transactions t WHERE t.invoice_id = i.id)", Long.class, cardId);
        return n == null ? 0 : n;
    }

    /**
     * Trava a linha do cartão numa conexão à parte, enfileira a compra P e depois a mudança de dias A (A já travou as linhas
     * de movimentação existentes e espera o cartão), solta a trava; assim que P confirma, dispara {@code follow(T)} sobre a
     * movimentação nova T, que A não travou. Devolve {mudança, seguimento}.
     */
    private List<CardQaWorld.Res> changeDaysThenFollowNewTx(String cardId, int closing, int due, java.util.function.Function<String, Callable<CardQaWorld.Res>> follow)
            throws Exception {
        JsonNode card0 = a.getOk("/v1/cards/" + cardId);
        String confirmed = a.getOk("/v1/cards/" + cardId + "/billing-change-preview?closingDay=" + closing + "&dueDay=" + due)
                .get("effectiveFromClosingDate").asString();
        ExecutorService pool = Executors.newFixedThreadPool(3);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM credit_cards WHERE id = ?::uuid FOR UPDATE")) {
                st.setString(1, cardId);
                st.executeQuery();
            }
            Future<CardQaWorld.Res> purchase = pool.submit(() -> a.purchaseOn(cardId, a.tech, "300.00", 3, "2026-10-12", "Nova"));
            waitBlocked(1);
            Future<CardQaWorld.Res> change = pool.submit(() -> a.putDays(cardId, closing, due, confirmed, card0.get("version").asInt()));
            waitBlocked(2);
            holder.rollback();
            CardQaWorld.Res created = purchase.get();
            CardQaWorld.ok(created);
            Future<CardQaWorld.Res> second = pool.submit(follow.apply(created.node().get("id").asString()));
            return List.of(change.get(), second.get(), created);
        } finally {
            pool.shutdownNow();
        }
    }

    private void waitBlocked(int count) throws Exception {
        for (int i = 0; i < 100; i++) {
            Long n = jdbc.queryForObject("SELECT count(*) FROM pg_locks WHERE NOT granted", Long.class);
            if (n != null && n >= count) {
                return;
            }
            Thread.sleep(50);
        }
        throw new AssertionError("a corrida não chegou a enfileirar " + count + " espera(s) de trava");
    }

    private void seedPurchases(String cardId, int n) throws Exception {
        for (int i = 0; i < n; i++) {
            CardQaWorld.ok(a.purchaseOn(cardId, a.tech, "120.00", 6, "2026-10-1" + (i % 9), "Seed " + i));
        }
    }

    // ------------------------------------------------------------------ S-215: mudança de dias x estorno concorrente

    @Test
    @DisplayName("S-215: mudança de dias x estorno de compra criada DURANTE a espera da mudança: sem deadlock (nenhum 5xx), os dois terminam")
    void mudancaDeDiasContraEstornoDeCompraNova() throws Exception {
        seedPurchases(a.card, 30);
        List<String> failures = new ArrayList<>();
        int closing = 10;
        for (int round = 0; round < 6; round++) {
            List<CardQaWorld.Res> r = changeDaysThenFollowNewTx(a.card, closing, 5, id -> () -> a.voidTx(id));
            if (r.get(0).status() >= 500 || r.get(1).status() >= 500) {
                failures.add("rodada " + round + ": mudança=" + r.get(0) + " | estorno=" + r.get(1));
            }
            closing = closing == 10 ? 25 : 10;
        }
        assertThat(failures).as("deadlock entre a mudança de dias (trava todas as movimentações, depois o cartão) e o estorno "
                + "(trava a movimentação, depois o cartão) de uma movimentação criada depois do snapshot das travas").isEmpty();
    }

    @Test
    @DisplayName("S-215 (variante): mudança de dias x cancel-installments de compra criada durante a espera: sem 5xx")
    void mudancaDeDiasContraCancelamentoDeCompraNova() throws Exception {
        seedPurchases(a.card, 30);
        List<String> failures = new ArrayList<>();
        int closing = 10;
        for (int round = 0; round < 6; round++) {
            List<CardQaWorld.Res> r = changeDaysThenFollowNewTx(a.card, closing, 5, id -> () -> a.cancel(id));
            if (r.get(0).status() >= 500 || r.get(1).status() >= 500) {
                failures.add("rodada " + round + ": mudança=" + r.get(0) + " | cancel=" + r.get(1));
            }
            closing = closing == 10 ? 25 : 10;
        }
        assertThat(failures).isEmpty();
    }

    @Test
    @DisplayName("mudança de dias x compras simultâneas (8 compras + 1 mudança, 4 rodadas): sem 5xx, todas as compras criadas, invariante do cartão, sem linha órfã")
    void mudancaDeDiasContraCompras() throws Exception {
        seedPurchases(a.card, 5);
        int closing = 10;
        for (int round = 0; round < 4; round++) {
            JsonNode card0 = a.cardNow();
            int target = closing;
            String confirmed = a.getOk("/v1/cards/" + a.card + "/billing-change-preview?closingDay=" + target + "&dueDay=5")
                    .get("effectiveFromClosingDate").asString();
            List<Callable<CardQaWorld.Res>> tasks = new ArrayList<>();
            tasks.add(() -> a.putDays(a.card, target, 5, confirmed, card0.get("version").asInt()));
            for (int i = 0; i < 8; i++) {
                int n = i;
                tasks.add(() -> a.purchase("100.00", 1 + (n % 4), "2026-10-1" + n, "Corrida " + n));
            }
            List<CardQaWorld.Res> r = race(tasks);
            assertNoServerError("rodada " + round, r);
            // a mudança só pode perder para a confirmação velha (a compra cria fatura nova antes) ou ganhar
            assertThat(r.get(0).status()).as(r.get(0).toString()).isIn(200, 409);
            r.subList(1, r.size()).forEach(x -> assertThat(x.status()).as(x.toString()).isEqualTo(201));
            assertHealthy(a.card);
            assertThat(orphanRows(a.card)).isZero();
            closing = closing == 10 ? 25 : 10;
        }
    }

    @Test
    @DisplayName("mudança de dias x estorno de compra antiga (várias rodadas, 12 compras): sem 5xx e estado coerente")
    void mudancaDeDiasContraEstornosAntigos() throws Exception {
        List<String> ids = new ArrayList<>();
        for (int i = 0; i < 12; i++) {
            ids.add(CardQaWorld.ok(a.purchase("90.00", 3, "2026-10-1" + (i % 9), "Antiga " + i)).node().get("id").asString());
        }
        JsonNode card0 = a.cardNow();
        String confirmed = a.getOk("/v1/cards/" + a.card + "/billing-change-preview?closingDay=10&dueDay=5").get("effectiveFromClosingDate").asString();
        List<Callable<CardQaWorld.Res>> tasks = new ArrayList<>();
        tasks.add(() -> a.putDays(a.card, 10, 5, confirmed, card0.get("version").asInt()));
        ids.forEach(id -> tasks.add(() -> a.voidTx(id)));
        List<CardQaWorld.Res> r = race(tasks);
        assertNoServerError("estornos x mudança", r);
        assertThat(r.get(0).status()).isEqualTo(200);
        assertHealthy(a.card);
        assertThat(orphanRows(a.card)).isZero();
    }

    // ------------------------------------------------------------------ pagamentos

    @Test
    @DisplayName("6 pagamentos de 300,00 simultâneos (chaves diferentes) da mesma fatura de 1.283,00: 5 x 200 em série e o 6º é 422 INVOICE_NOT_PAYABLE (já PAID); excedente vira creditCarry")
    void pagamentosSimultaneosDaMesmaFatura() throws Exception {
        CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook"));
        CardQaWorld.ok(a.purchase("1083.00", 1, "2026-10-10", "Mercado"));
        a.at(OCT_30);
        UUID invoice = a.invoiceId(LocalDate.of(2026, 10, 25));
        List<Callable<CardQaWorld.Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            tasks.add(() -> a.pay(invoice, "300.00", "2026-10-30", null));
        }
        List<CardQaWorld.Res> r = race(tasks);
        assertNoServerError("pagamentos", r);
        assertThat(histogram(r)).as(r.toString()).isEqualTo(Map.of(200, 5, 422, 1));
        JsonNode inv = a.getOk("/v1/invoices/" + invoice);
        assertThat(inv.get("payments")).hasSize(5);
        assertThat(inv.get("status").asString()).isEqualTo("PAID");
        assertThat(inv.get("paid").get("amount").asString()).isEqualTo("1500.00");
        assertThat(inv.get("creditCarry").get("amount").asString()).isEqualTo("217.00");
        assertHealthy(a.card);
        assertThat(a.getOk("/v1/accounts/" + a.nubank).get("currentBalance").get("amount").asString()).isEqualTo("18500.00");
    }

    @Test
    @DisplayName("8 pagamentos simultâneos com a MESMA Idempotency-Key: um pagamento só, todas as respostas 200")
    void mesmaChaveEmParalelo() throws Exception {
        CardQaWorld.ok(a.purchase("500.00", 1, "2026-10-10", "Mercado"));
        a.at(OCT_30);
        UUID invoice = a.invoiceId(LocalDate.of(2026, 10, 25));
        String key = UUID.randomUUID().toString();
        List<Callable<CardQaWorld.Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> a.pay(invoice, "200.00", "2026-10-30", key));
        }
        List<CardQaWorld.Res> r = race(tasks);
        assertNoServerError("mesma chave", r);
        assertThat(histogram(r)).as(r.toString()).isEqualTo(Map.of(200, 8));
        assertThat(a.getOk("/v1/invoices/" + invoice).get("payments")).hasSize(1);
        assertHealthy(a.card);
    }

    @Test
    @DisplayName("S-14: compra x pagamento total da fatura CLOSED (8 rodadas): nunca um item novo numa fatura que o pagamento acabou de quitar")
    void compraContraPagamento() throws Exception {
        List<String> violations = new ArrayList<>();
        for (int round = 0; round < 8; round++) {
            clock.set(MutableClockConfig.INITIAL);
            CardQaWorld.Actor local = world.new Actor("s14");
            CardQaWorld.ok(local.purchase("1283.00", 1, "2026-10-10", "Mercado"));
            local.at(OCT_30);
            UUID invoice = local.invoiceId(LocalDate.of(2026, 10, 25));
            List<Callable<CardQaWorld.Res>> tasks = List.of(
                    () -> local.pay(invoice, "1283.00", "2026-10-30", null),
                    () -> local.purchase("90.00", 1, "2026-10-20", "Tardia"));
            List<CardQaWorld.Res> r = race(tasks);
            assertNoServerError("rodada " + round, r);
            assertThat(r.get(0).status()).as(r.get(0).toString()).isEqualTo(200);
            assertThat(r.get(1).status()).as(r.get(1).toString()).isEqualTo(201);
            String paidStatus = r.get(0).node().get("status").asString();
            String purchaseClosing = local.getOk("/v1/invoices/" + r.get(1).node().get("installments").get(0).get("invoiceId").asString())
                    .get("closingDate").asString();
            // o pagamento enxergou PAID e a compra mesmo assim entrou na fatura de 25/10: o item caiu numa fatura fechada e quitada
            if ("PAID".equals(paidStatus) && "2026-10-25".equals(purchaseClosing)) {
                violations.add("rodada " + round + ": pagamento respondeu PAID e a compra tardia entrou em 25/10");
            }
            assertThat(CardInvariants.check(local, local.card)).isEmpty();
        }
        assertThat(violations).isEmpty();
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("reembolso ligado à compra x estorno da compra (10 rodadas): nunca os dois dão certo")
    void reembolsoContraEstorno() throws Exception {
        for (int round = 0; round < 10; round++) {
            String purchase = CardQaWorld.ok(a.purchase("200.00", 1, "2026-10-10", "Compra " + round)).node().get("id").asString();
            List<Callable<CardQaWorld.Res>> tasks = List.of(
                    () -> a.cardRefund("50.00", "2026-10-11", purchase),
                    () -> a.voidTx(purchase));
            List<CardQaWorld.Res> r = race(tasks);
            assertNoServerError("rodada " + round, r);
            boolean refundOk = r.get(0).status() == 201;
            boolean voidOk = r.get(1).status() == 200;
            assertThat(refundOk && voidOk).as("estorno e reembolso ativo da mesma compra: " + r).isFalse();
            assertThat(refundOk || voidOk).as(r.toString()).isTrue();
        }
        assertHealthy(a.card);
    }

    @Test
    @DisplayName("cancel-installments x pagamento antecipado da fatura aberta (6 rodadas): sem 5xx, invariante e razão fecham")
    void cancelamentoContraPagamento() throws Exception {
        for (int round = 0; round < 6; round++) {
            String cardId = a.newCard("Rodada " + round, 25, 5, "10000.00");
            String purchase = CardQaWorld.ok(a.purchaseOn(cardId, a.tech, "1200.00", 6, "2026-10-10", "Notebook " + round)).node().get("id").asString();
            UUID invoice = com.moneylog.core.card.domain.InvoiceIds.of(UUID.fromString(cardId), LocalDate.of(2026, 10, 25));
            List<Callable<CardQaWorld.Res>> tasks = List.of(
                    () -> a.cancel(purchase),
                    () -> a.pay(invoice, "200.00", "2026-10-10", null));
            List<CardQaWorld.Res> r = race(tasks);
            assertNoServerError("rodada " + round, r);
            assertThat(CardInvariants.check(a, cardId)).as("rodada " + round + " " + r).isEmpty();
        }
        assertThat(world.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("estorno de um pagamento x novo pagamento da mesma fatura (cartão novo por rodada): sem 5xx e o invariante do cartão fecha")
    void estornoDePagamentoContraPagamento() throws Exception {
        for (int round = 0; round < 5; round++) {
            a.at(MutableClockConfig.INITIAL);
            String cardId = a.newCard("EP " + round, 25, 5, "10000.00");
            CardQaWorld.ok(a.purchaseOn(cardId, a.tech, "1000.00", 1, "2026-10-10", "Mercado"));
            a.at(OCT_30);
            UUID invoice = com.moneylog.core.card.domain.InvoiceIds.of(UUID.fromString(cardId), LocalDate.of(2026, 10, 25));
            CardQaWorld.Res first = CardQaWorld.ok(a.pay(invoice, "400.00", "2026-10-30", null));
            String paymentTx = first.node().get("payments").get(first.node().get("payments").size() - 1).get("transactionId").asString();
            List<Callable<CardQaWorld.Res>> tasks = List.of(
                    () -> a.voidTx(paymentTx),
                    () -> a.pay(invoice, "700.00", "2026-10-30", null));
            List<CardQaWorld.Res> r = race(tasks);
            assertNoServerError("rodada " + round, r);
            assertThat(CardInvariants.check(a, cardId)).as("rodada " + round + " " + r).isEmpty();
        }
    }

    // ------------------------------------------------------------------ criação concorrente de fatura

    @Test
    @DisplayName("12 compras parceladas simultâneas num cartão sem linhas: todas 201, uma linha por fechamento (UUID v5 + ON CONFLICT), nenhuma duplicada")
    void criacaoConcorrenteDeFatura() throws Exception {
        for (int round = 0; round < 5; round++) {
            String cardId = a.newCard("Corrida " + round, 25, 5, "10000.00");
            List<Callable<CardQaWorld.Res>> tasks = new ArrayList<>();
            for (int i = 0; i < 12; i++) {
                int n = i;
                tasks.add(() -> a.purchaseOn(cardId, a.tech, "99.00", 3, "2026-10-10", "Compra " + n));
            }
            List<CardQaWorld.Res> r = race(tasks);
            assertNoServerError("rodada " + round, r);
            assertThat(histogram(r)).as(r.toString()).isEqualTo(Map.of(201, 12));
            assertThat(jdbc.queryForObject("SELECT count(*) FROM credit_card_invoices WHERE card_id = ?::uuid", Long.class, cardId)).isEqualTo(3L);
            assertThat(jdbc.queryForObject("SELECT count(DISTINCT closing_date) FROM credit_card_invoices WHERE card_id = ?::uuid", Long.class, cardId)).isEqualTo(3L);
            assertThat(CardInvariants.check(a, cardId)).isEmpty();
        }
    }

    @Test
    @DisplayName("5 usuários, 5 cartões de mesmo fechamento comprando ao mesmo tempo: os ids de fatura são distintos por cartão e todos resolvem")
    void idsDeFaturaDistintosPorCartao() throws Exception {
        List<CardQaWorld.Actor> actors = new ArrayList<>();
        for (int i = 0; i < 5; i++) {
            actors.add(world.new Actor("ids"));
        }
        List<Callable<CardQaWorld.Res>> tasks = new ArrayList<>();
        actors.forEach(x -> tasks.add(() -> x.purchase("10.00", 1, "2026-10-10", "x")));
        assertThat(histogram(race(tasks))).isEqualTo(Map.of(201, 5));
        java.util.Set<UUID> ids = new java.util.HashSet<>();
        for (CardQaWorld.Actor x : actors) {
            ids.addAll(jdbc.queryForList("SELECT id FROM credit_card_invoices WHERE user_id = ?", UUID.class, x.id));
        }
        assertThat(ids).hasSize(5);
    }

    // ------------------------------------------------------------------ revisor B1: validação do pagamento depois da trava

    @Test
    @DisplayName("B1: pagamento espera a trava do cartão enquanto um estorno do pagamento anterior confirma: o novo pagamento vê a fatura de novo CLOSED (200), não a PAID velha (422)")
    void pagamentoEsperandoATravaVeOEstadoConfirmadoNoMeio() throws Exception {
        CardQaWorld.ok(a.purchase("100.00", 1, "2026-10-10", "Mercado"));
        a.at(OCT_30);
        UUID invoice = a.invoiceId(LocalDate.of(2026, 10, 25));
        CardQaWorld.Res first = CardQaWorld.ok(a.pay(invoice, "100.00", "2026-10-30", null));
        assertThat(first.node().get("status").asString()).isEqualTo("PAID");
        String paymentTx = first.node().get("payments").get(0).get("transactionId").asString();
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM credit_cards WHERE id = ?::uuid FOR UPDATE")) {
                st.setString(1, a.card);
                st.executeQuery();
            }
            Future<CardQaWorld.Res> voided = pool.submit(() -> a.voidTx(paymentTx));
            waitBlocked(1);
            Future<CardQaWorld.Res> second = pool.submit(() -> a.pay(invoice, "100.00", "2026-10-30", null));
            waitBlocked(2);
            holder.rollback();
            assertThat(voided.get().status()).as(voided.get().toString()).isEqualTo(200);
            assertThat(second.get().status()).as("o estorno confirmou antes de o pagamento obter a trava: a fatura voltou a CLOSED e é paga: "
                    + second.get()).isEqualTo(200);
        } finally {
            pool.shutdownNow();
        }
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
    }

    @Test
    @DisplayName("B1 (variante): pagamento total esperando a trava enquanto um cancel-installments confirma: o resultado final é coerente (invariante)")
    void pagamentoEsperandoACancelamento() throws Exception {
        String purchase = CardQaWorld.ok(a.purchase("1200.00", 6, "2026-10-10", "Notebook")).node().get("id").asString();
        UUID invoice = a.invoiceId(LocalDate.of(2026, 10, 25));
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try (Connection holder = dataSource.getConnection()) {
            holder.setAutoCommit(false);
            try (var st = holder.prepareStatement("SELECT id FROM credit_cards WHERE id = ?::uuid FOR UPDATE")) {
                st.setString(1, a.card);
                st.executeQuery();
            }
            Future<CardQaWorld.Res> cancel = pool.submit(() -> a.cancel(purchase));
            waitBlocked(1);
            Future<CardQaWorld.Res> pay = pool.submit(() -> a.pay(invoice, "200.00", "2026-10-10", null));
            waitBlocked(2);
            holder.rollback();
            assertThat(cancel.get().status()).as(cancel.get().toString()).isEqualTo(200);
            assertThat(pay.get().status()).as(pay.get().toString()).isLessThan(500);
        } finally {
            pool.shutdownNow();
        }
        assertThat(CardInvariants.check(a, a.card)).isEmpty();
    }
}
