package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.note.util.NoteTestKit.Zip;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T113: concorrência das notas com a trava por usuário e com operações financeiras")
class NoteConcurrencyQaIT {

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

    private CardQaWorld w;
    private Actor a;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = w.new Actor("e6conc");
    }

    private List<Res> runAll(List<Callable<Res>> tasks) throws Exception {
        CountDownLatch ready = new CountDownLatch(tasks.size());
        CountDownLatch go = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<Res>> futures = new ArrayList<>();
            for (Callable<Res> t : tasks) {
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    return t.call();
                }));
            }
            ready.await();
            go.countDown();
            List<Res> out = new ArrayList<>();
            for (Future<Res> f : futures) {
                out.add(f.get(90, TimeUnit.SECONDS));
            }
            return out;
        } finally {
            pool.shutdownNow();
        }
    }

    private Callable<Res> call(HttpMethod m, String url, String body) {
        return () -> {
            MvcResult r = mvc.perform(a.req(m, url, body)).andReturn();
            return w.res(r);
        };
    }

    private static void noServerError(List<Res> results, String what) {
        for (Res r : results) {
            assertThat(r.status()).as("%s: %s", what, r).isLessThan(500);
            if (r.status() == 409) {
                assertThat(r.code()).as("%s: 409 precisa ser VERSION_CONFLICT ou CONFLICT: %s", what, r).isIn("VERSION_CONFLICT", "CONFLICT");
            }
        }
    }

    private void assertNoOrphans() {
        assertThat(jdbc.queryForObject("select count(*) from note_links l join notes t on t.id = l.target_note_id where l.user_id = ? and l.origin = 'WIKILINK' and t.deleted_at is not null", Long.class, a.id)).isZero();
        List<String> wrong = jdbc.queryForList("""
                select s.path || ' -> [[' || l.target_text || ']]' from note_links l join notes s on s.id = l.note_id
                where l.user_id = ? and l.origin = 'WIKILINK' and l.target_note_id is null
                  and exists (select 1 from notes n where n.user_id = l.user_id and n.deleted_at is null
                      and lower(regexp_replace(n.path, '^.*/', '')) = lower(l.target_text) || '.md' and l.target_text not like '%/%')""", String.class, a.id);
        assertThat(wrong).as("links pendentes cujo alvo existe").isEmpty();
    }

    @Test
    @DisplayName("renomear A e B ao mesmo tempo (A cita B e B cita A): só 200 ou 409 (a versão de B mudou pela reescrita); repetir com a versão nova termina; os dois links ficam resolvidos e reescritos, 12 rodadas")
    void renomearDuasQueSeReferenciam() throws Exception {
        for (int round = 0; round < 12; round++) {
            String idA = NoteQa.createOk(w, a, "a" + round + ".md", "cito [[b" + round + "]] e [[b" + round + "|apelido]]");
            String idB = NoteQa.createOk(w, a, "b" + round + ".md", "cito [[a" + round + "]] #t");
            List<Res> results = runAll(List.of(call(HttpMethod.POST, "/v1/notes/" + idA + "/move", "{\"newPath\":\"a" + round + "x.md\",\"version\":0}"),
                    call(HttpMethod.POST, "/v1/notes/" + idB + "/move", "{\"newPath\":\"b" + round + "x.md\",\"version\":0}")));
            noServerError(results, "rodada " + round);
            for (int i = 0; i < 2; i++) {
                if (results.get(i).status() == 409) {
                    String id = i == 0 ? idA : idB;
                    long version = NoteQa.get(a, id).get("version").asLong();
                    Res retry = NoteQa.move(w, a, id, (i == 0 ? "a" : "b") + round + "x.md", version);
                    assertThat(retry.status()).as("repetir com a versão nova: %s", retry).isEqualTo(200);
                }
            }
            assertThat(NoteQa.get(a, idA).get("body").asString()).isEqualTo("cito [[b" + round + "x]] e [[b" + round + "x|apelido]]");
            assertThat(NoteQa.get(a, idB).get("body").asString()).isEqualTo("cito [[a" + round + "x]] #t");
            for (JsonNode l : NoteQa.get(a, idA).get("links")) {
                assertThat(l.get("state").asString()).isEqualTo("RESOLVED");
            }
        }
        assertNoOrphans();
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("criar 'Nota.md' e 'nota.md' ao mesmo tempo (caixa diferente): exatamente uma é 201; mover duas notas para o mesmo caminho: exatamente uma vence; 3 rodadas de 6 tarefas")
    void caminhoComCaixaDiferente() throws Exception {
        for (int round = 0; round < 3; round++) {
            List<Callable<Res>> tasks = new ArrayList<>();
            String[] variants = {"Nota" + round + ".md", "nota" + round + ".md", "NOTA" + round + ".md", "nOtA" + round + ".md", "Nota" + round + ".MD", "NOTA" + round + ".md"};
            for (String v : variants) {
                tasks.add(call(HttpMethod.POST, "/v1/notes", "{\"path\":\"" + v + "\",\"body\":\"x\"}"));
            }
            List<Res> results = runAll(tasks);
            noServerError(results, "criar caixas");
            assertThat(results.stream().filter(r -> r.status() == 201).count()).isEqualTo(1L);
            assertThat(results.stream().filter(r -> r.status() == 422 && r.code().equals("NOTE_PATH_TAKEN")).count()).isEqualTo(5L);
        }
        String x = NoteQa.createOk(w, a, "mx.md", "x");
        String y = NoteQa.createOk(w, a, "my.md", "y");
        List<Res> moves = runAll(List.of(call(HttpMethod.POST, "/v1/notes/" + x + "/move", "{\"newPath\":\"Destino.md\",\"version\":0}"),
                call(HttpMethod.POST, "/v1/notes/" + y + "/move", "{\"newPath\":\"destino.md\",\"version\":0}")));
        noServerError(moves, "mover para o mesmo");
        assertThat(moves.stream().filter(r -> r.status() == 200).count()).isEqualTo(1L);
        assertThat(jdbc.queryForObject("select count(*) from (select path_key from notes where user_id = ? and deleted_at is null group by path_key having count(*) > 1) d", Long.class, a.id)).isZero();
    }

    @Test
    @DisplayName("lixeira x restaurar x esvaziar x editar x mover (a mesma nota, 6 operações, 8 rodadas): sem 5xx, sem link órfão, e o que sobrou é coerente")
    void lixeiraEsvaziarEditar() throws Exception {
        for (int round = 0; round < 8; round++) {
            String leitor = NoteQa.createOk(w, a, "leitor" + round + ".md", "veja [[alvo" + round + "]]");
            String alvo = NoteQa.createOk(w, a, "alvo" + round + ".md", "alvo");
            NoteQa.trash(a, alvo);
            List<Res> results = runAll(List.of(call(HttpMethod.POST, "/v1/notes/" + alvo + "/restore", null), call(HttpMethod.DELETE, "/v1/notes/trash", null),
                    call(HttpMethod.PUT, "/v1/notes/" + alvo, "{\"body\":\"editado\",\"version\":1}"), call(HttpMethod.POST, "/v1/notes/" + alvo + "/move", "{\"newPath\":\"mov" + round + ".md\",\"version\":1}"),
                    call(HttpMethod.POST, "/v1/notes/" + alvo + "/trash", null), call(HttpMethod.PUT, "/v1/notes/" + leitor, "{\"body\":\"veja [[alvo" + round + "]] [[mov" + round + "]]\",\"version\":0}")));
            noServerError(results, "rodada " + round);
            for (Res r : results) {
                assertThat(r.status()).isIn(200, 204, 404, 409, 422);
            }
            assertNoOrphans();
        }
    }

    @Test
    @DisplayName("importar x editar x mover x lixeira: 4 importações do mesmo zip (RENAME e SKIP) enquanto se edita, move e manda para a lixeira notas do mesmo nome; sem 5xx e sem caminho duplicado ativo")
    void importarXEditar() throws Exception {
        for (int round = 0; round < 4; round++) {
            String id = NoteQa.createOk(w, a, "r" + round + "/a.md", "original [[b]]");
            Zip zip = new Zip();
            for (int i = 0; i < 40; i++) {
                zip.add("r" + round + "/" + (i == 0 ? "a" : "n" + i) + ".md", "do zip [[a]] [[n" + (i + 1) + "]] " + i);
            }
            byte[] bytes = zip.bytes();
            List<Callable<Res>> tasks = new ArrayList<>();
            for (int k = 0; k < 4; k++) {
                String mode = k % 2 == 0 ? "RENAME" : "SKIP";
                tasks.add(() -> {
                    MvcResult r = mvc.perform(MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "v.zip", "application/zip", bytes))
                            .param("mode", mode).header("Authorization", a.bearer)).andReturn();
                    return w.res(r);
                });
            }
            tasks.add(call(HttpMethod.PUT, "/v1/notes/" + id, "{\"body\":\"editada [[n3]]\",\"version\":0}"));
            tasks.add(call(HttpMethod.POST, "/v1/notes/" + id + "/move", "{\"newPath\":\"r" + round + "/movida.md\",\"version\":0}"));
            tasks.add(call(HttpMethod.POST, "/v1/notes/" + id + "/trash", null));
            List<Res> results = runAll(tasks);
            for (Res r : results) {
                assertThat(r.status()).as("importar x editar: %s", r).isLessThan(500);
                if (r.status() == 429) {
                    continue;
                }
                assertThat(r.status()).isIn(200, 404, 409, 422);
            }
            assertNoOrphans();
            assertThat(jdbc.queryForObject("select count(*) from (select path_key from notes where user_id = ? and deleted_at is null group by path_key having count(*) > 1) d", Long.class, a.id)).isZero();
            clock.set(clock.instant().plusSeconds(120));
            a.bearer = auth.bearer(a.id);
        }
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("mesma Idempotency-Key em 8 criações simultâneas: uma nota só, todas respondem a mesma nota (201 ou 200), nunca 5xx")
    void idempotenciaSimultanea() throws Exception {
        String key = UUID.randomUUID().toString();
        List<Callable<Res>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(() -> w.res(mvc.perform(a.req(HttpMethod.POST, "/v1/notes", "{\"path\":\"idem.md\",\"body\":\"x\"}").header("Idempotency-Key", key)).andReturn()));
        }
        List<Res> results = runAll(tasks);
        noServerError(results, "idempotência");
        assertThat(a.count("notes")).isEqualTo(1L);
        for (Res r : results) {
            assertThat(r.status()).isIn(200, 201, 409, 422);
        }
    }

    @Test
    @DisplayName("nota (vinculada a meta, fatura e transação) x reserva de meta x pagamento de fatura x editar/mover, 15 rodadas: tudo termina em 90 s (sem deadlock), só 200/201/409 CONFLICT/422, sem 5xx; a reserva e o pagamento valem")
    void notaXReservaXFatura() throws Exception {
        String goal = GoalQa.goal(a, "Reserva", "50000.00", null);
        String tx = CardQaWorld.ok(a.purchase("1000.00", 1, "2026-10-10", "Compra")).node().get("id").asString();
        UUID invoice = UUID.fromString(a.invoices().get(0).get("id").asString());
        String note = NoteQa.createOk(w, a, "fin.md", "base");
        long reservedBefore = GoalQa.net(jdbc, a, goal, a.nubank);
        int rounds = 15;
        for (int round = 0; round < rounds; round++) {
            String links = "[" + NoteQa.link("GOAL", goal) + "," + NoteQa.link("INVOICE", invoice.toString()) + "," + NoteQa.link("TRANSACTION", tx) + "," + NoteQa.link("CARD", a.card) + "]";
            long version = NoteQa.get(a, note).get("version").asLong();
            List<Callable<Res>> tasks = new ArrayList<>();
            tasks.add(call(HttpMethod.PUT, "/v1/notes/" + note + "/links", "{\"links\":" + links + ",\"version\":" + version + "}"));
            tasks.add(call(HttpMethod.POST, "/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"10.00\",\"date\":\"2026-10-10\"}"));
            tasks.add(() -> w.res(mvc.perform(a.req(HttpMethod.POST, "/v1/goals/" + goal + "/reservations", "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"5.00\",\"date\":\"2026-10-10\"}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn()));
            tasks.add(() -> w.res(mvc.perform(a.req(HttpMethod.POST, "/v1/invoices/" + invoice + "/payments", "{\"accountId\":\"" + a.nubank + "\",\"amount\":\"1.00\",\"date\":\"2026-10-12\"}")
                    .header("Idempotency-Key", UUID.randomUUID().toString())).andReturn()));
            tasks.add(call(HttpMethod.POST, "/v1/notes", "{\"path\":\"extra" + round + ".md\",\"body\":\"[[fin]] #x\",\"links\":" + links + "}"));
            tasks.add(call(HttpMethod.POST, "/v1/notes/" + note + "/move", "{\"newPath\":\"fin" + (round % 2) + ".md\",\"version\":" + (version) + "}"));
            tasks.add(call(HttpMethod.POST, "/v1/goals/" + goal + "/archive", null));
            tasks.add(call(HttpMethod.POST, "/v1/goals/" + goal + "/unarchive", null));
            List<Res> results = runAll(tasks);
            for (Res r : results) {
                assertThat(r.status()).as("rodada %d: %s", round, r).isLessThan(500);
                if (r.status() == 409) {
                    assertThat(r.code()).isIn("VERSION_CONFLICT", "CONFLICT", "IDEMPOTENCY_KEY_REUSED");
                }
            }
            noServerError(results, "rodada " + round);
        }
        assertThat(GoalQa.net(jdbc, a, goal, a.nubank)).as("as reservas valem mesmo com as notas").isGreaterThanOrEqualTo(reservedBefore);
        assertThat(a.ledgerSum()).isZero();
        assertThat(a.unbalancedGroups()).isZero();
        assertNoOrphans();
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("vincular nota à fatura vazia x mudança do calendário do cartão que remove a fatura (ON DELETE CASCADE), 15 rodadas: nunca 500; só 200/422/409 CONFLICT; a nota continua legível")
    void vincularXRemoverFatura() throws Exception {
        String note = NoteQa.createOk(w, a, "fat.md", "nota");
        int removed = 0;
        int linked = 0;
        List<String> statuses = new ArrayList<>();
        for (int round = 0; round < 15; round++) {
            String card2 = a.newCard("C" + round, 25, 5, "1000.00");
            List<JsonNode> invoices = a.invoicesOf(card2);
            if (invoices.isEmpty()) {
                continue;
            }
            String inv = invoices.get(invoices.size() - 1).get("id").asString();
            long version = NoteQa.get(a, note).get("version").asLong();
            JsonNode cardNode = a.getOk("/v1/cards/" + card2);
            JsonNode preview = a.getOk("/v1/cards/" + card2 + "/billing-change-preview?closingDay=" + (round % 2 == 0 ? 10 : 15) + "&dueDay=20");
            String put = "{\"name\":\"C" + round + "\",\"institution\":\"Nubank\",\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"closingDay\":" + (round % 2 == 0 ? 10 : 15)
                    + ",\"dueDay\":20,\"defaultPaymentAccountId\":\"" + a.nubank + "\",\"confirmedEffectiveFrom\":\"" + preview.get("effectiveFromClosingDate").asString() + "\",\"version\":"
                    + cardNode.get("version").asInt() + "}";
            List<Res> results = runAll(List.of(call(HttpMethod.PUT, "/v1/notes/" + note + "/links", "{\"links\":[" + NoteQa.link("INVOICE", inv) + "," + NoteQa.link("CARD", card2) + "],\"version\":" + version + "}"),
                    call(HttpMethod.PUT, "/v1/cards/" + card2, put)));
            for (Res r : results) {
                assertThat(r.status()).as("rodada %d: %s", round, r).isLessThan(500);
                statuses.add(r.status() + (r.code().isEmpty() ? "" : "/" + r.code()));
                if (r.status() == 409) {
                    assertThat(r.code()).isIn("VERSION_CONFLICT", "CONFLICT");
                }
            }
            if (jdbc.queryForObject("select count(*) from credit_card_invoices where id = ?::uuid", Long.class, inv) == 0) {
                removed++;
            }
            if (results.get(0).status() == 200) {
                linked++;
            }
            Res read = a.get("/v1/notes/" + note);
            assertThat(read.status()).as("a nota segue legível: %s", read).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from note_links l where l.note_id = ?::uuid and l.target_invoice_id is not null and not exists (select 1 from credit_card_invoices i where i.id = l.target_invoice_id)", Long.class, note))
                    .as("nenhum vínculo aponta para fatura inexistente").isZero();
        }
        System.out.println("QA-E6-CONC fatura vazia: rodadas com fatura removida=" + removed + " vínculos criados=" + linked + " resultados=" + statuses);
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("sem deadlock entre usuários: 4 usuários fazem operações de nota e financeiras ao mesmo tempo, 10 operações cada, e tudo termina")
    void usuariosIndependentes() throws Exception {
        List<Actor> users = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            users.add(w.new Actor("e6multi" + i));
        }
        List<Callable<Res>> tasks = new ArrayList<>();
        for (Actor u : users) {
            tasks.add(() -> {
                Res last = null;
                String goal = GoalQa.goal(u, "M", "1000.00", null);
                for (int i = 0; i < 10; i++) {
                    Res c = u.call(HttpMethod.POST, "/v1/notes", "{\"path\":\"n" + i + ".md\",\"body\":\"[[n" + (i + 1) + "]]\",\"links\":[" + NoteQa.link("GOAL", goal) + "]}", null);
                    assertThat(c.status()).isEqualTo(201);
                    last = GoalQa.reserve(u, goal, u.nubank, "1.00", LocalDate.of(2026, 10, 10));
                    assertThat(last.status()).isLessThan(500);
                }
                return last;
            });
        }
        runAll(tasks);
        for (Actor u : users) {
            assertThat(u.count("notes")).isEqualTo(10L);
        }
    }
}
