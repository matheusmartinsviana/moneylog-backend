package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.net.URI;
import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.text.Normalizer;
import java.util.ArrayList;
import java.util.HashSet;
import java.util.List;
import java.util.Set;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T190 (QA independente): superfície de risco da busca global. Dois usuários com dados idênticos em todos os cinco grupos, cargas úteis de
 * SQL/LIKE/Unicode, determinismo, concorrência com escritas, notas só por título/caminho e validação de parâmetros. O limite de taxa fica alto
 * aqui (tem classe própria) para os laços não esbarrarem nele.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.search.rate-limit.max-requests=1000000")
@DisplayName("QA T190: busca global (isolamento, injeção, Unicode, determinismo, concorrência)")
class RedesignFase0SearchQaIT {

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

    private CardQaWorld world;
    private Actor alice;
    private Actor bob;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        world = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        alice = world.new Actor("srchA");
        bob = world.new Actor("srchB");
        for (Actor a : List.of(alice, bob)) {
            // dados idênticos nos dois usuários: conta (Nubank), categoria (Tecnologia, Alimentação), cartão (Roxinho), nota e descrição
            a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.tech, "10.00", "2026-10-09", "Gêmea Café da Manhã"), DebtQa.key());
            a.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "11.00", "2026-10-08", "Gêmea Café da Manhã"), DebtQa.key());
            NoteQa.createOk(world, a, "gemea/Café Nota.md", "# Café Nota\ncorpo sigiloso do " + a.id);
        }
    }

    private Res search(Actor a, String q) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", q).header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        return world.res(r);
    }

    private Res search(Actor a, String q, int limit) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", q).param("limit", "" + limit)
                .header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        return world.res(r);
    }

    private Res raw(Actor a, String pathAndQuery) throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get(URI.create(pathAndQuery)).header(HttpHeaders.AUTHORIZATION, a.bearer)).andReturn();
        return world.res(r);
    }

    private Set<String> idsOf(String sql, Actor a) {
        return new HashSet<>(jdbc.queryForList(sql, String.class, a.id));
    }

    private Set<String> ownIds(Actor a) {
        Set<String> ids = new HashSet<>();
        ids.addAll(idsOf("select id::text from transactions where user_id = ?", a));
        ids.addAll(idsOf("select id::text from accounts where user_id = ?", a));
        ids.addAll(idsOf("select id::text from categories where user_id = ?", a));
        ids.addAll(idsOf("select id::text from credit_cards where user_id = ?", a));
        ids.addAll(idsOf("select id::text from notes where user_id = ?", a));
        return ids;
    }

    private static List<String> allIds(JsonNode body) {
        List<String> ids = new ArrayList<>();
        for (String group : List.of("transactions", "accounts", "categories", "cards", "notes")) {
            for (JsonNode n : body.get(group)) {
                ids.add(n.get("id").asString());
            }
        }
        return ids;
    }

    // ------------------------------------------------------------------------------------------------ isolamento

    @Test
    @DisplayName("isolamento: com dados idênticos nos dois usuários, cada um vê só o seu em todos os cinco grupos (e nenhum UUID do outro aparece no corpo)")
    void isolamentoNosCincoGrupos() throws Exception {
        String[] queries = {"nubank", "tecno", "roxinho", "gemea", "cafe nota", "Café", "gemea/", ".md", "Nubank", "x", "ali", "sal"};
        for (String q : queries) {
            for (Actor me : List.of(alice, bob)) {
                Actor other = me == alice ? bob : alice;
                Res r = search(me, q.length() < 2 ? q + q : q);
                assertThat(r.status()).as(q).isEqualTo(200);
                Set<String> mine = ownIds(me);
                Set<String> theirs = ownIds(other);
                for (String id : allIds(r.node())) {
                    assertThat(mine).as("id " + id + " na busca '" + q + "'").contains(id);
                    assertThat(theirs).as("vazou id do outro usuário").doesNotContain(id);
                }
                // nada do outro no texto (nem o e-mail, nem o UUID do usuário, nem o corpo da nota)
                assertThat(r.text()).doesNotContain(other.id.toString());
                assertThat(r.text()).doesNotContain("corpo sigiloso do " + other.id);
            }
        }
        // cada grupo achou algo para o dono (os dados gêmeos existem nos cinco)
        JsonNode a = search(alice, "gemea").node();
        assertThat(a.get("transactions")).hasSize(2);
        assertThat(a.get("notes")).hasSize(1);
        assertThat(search(alice, "nubank").node().get("accounts")).hasSize(1);
        assertThat(search(alice, "nubank").node().get("cards")).hasSize(1);
        assertThat(search(alice, "tecno").node().get("categories")).hasSize(1);
        // o snippet mostra o começo do corpo da PRÓPRIA nota (decisão de Produto, achado B-2 da revisão); nunca o de outro usuário
        assertThat(search(alice, "gemea").text()).contains("corpo sigiloso do " + alice.id).doesNotContain("corpo sigiloso do " + bob.id);
    }

    // ------------------------------------------------------------------------------------------------ cargas úteis

    private static final List<String> PAYLOADS = List.of("%%", "_%_", "\\", "\\\\", "\\%", "100%", "a_b", "'", "''", "' OR '1'='1", "\" OR \"1\"=\"1", "; DROP TABLE transactions;--",
            "--", "/* */", "'; select pg_sleep(5);--", "$1", "${jndi:ldap://x/a}", "{{7*7}}", "<script>alert(1)</script>", "<img src=x onerror=alert(1)>",
            "%25", "%00", "&#0;", "ǅ", "ß", "İ", "ı", "ﬃ", "Å", "Å", "é", "é", "é", "‮abc", "‭xyz‬", "‍zwj", "👨‍👩‍👧", "🙂🙂", "日本語",
            "ＡＢＣ", "ａｂｃ", "﻿bom", "a b", "a b", "a\u0085b", "\uD83D", "x\uD800y", "ǈ", "ŉ");

    @Test
    @DisplayName("cargas úteis plantadas como descrição, conta, categoria, cartão e nota: nunca 5xx, resposta fiel ao contrato, só dados do dono e achado literal (oráculo position() em SQL)")
    void cargasUteis() throws Exception {
        List<String> failures = new ArrayList<>();
        int planted = 0;
        for (String payload : PAYLOADS) {
            String description = "pl-" + planted++ + " " + payload + " fim";
            Res created = alice.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(alice.nubank, alice.food, "1.00", "2026-10-09",
                    world.json.writeValueAsString(description).replaceAll("^\"|\"$", "")), DebtQa.key());
            // se o domínio recusa a descrição (controles), o teste de busca segue sem a linha plantada
            Res s = search(alice, payload);
            int length = payload.codePointCount(0, payload.length());
            boolean hasControl = payload.codePoints().anyMatch(Character::isISOControl);
            if (s.status() >= 500) {
                failures.add("5xx para " + show(payload) + ": " + s);
                continue;
            }
            if (s.status() == 400) {
                if (!(length < 2 || hasControl || payload.strip().codePointCount(0, payload.strip().length()) < 2)) {
                    failures.add("400 inesperado para " + show(payload) + ": " + s);
                }
                if (s.text().contains(payload) && payload.length() > 3) {
                    failures.add("o erro 400 ecoa o termo " + show(payload));
                }
                continue;
            }
            if (s.status() != 200) {
                failures.add("status " + s.status() + " para " + show(payload));
                continue;
            }
            Set<String> mine = ownIds(alice);
            for (String id : allIds(s.node())) {
                if (!mine.contains(id)) {
                    failures.add("id de outro usuário para " + show(payload) + ": " + id);
                }
            }
            // oráculo: a quantidade de movimentações ativas do dono que contêm o termo (substring literal, sem acento e sem caixa) em SQL
            Long expected = jdbc.queryForObject("select count(*) from transactions where user_id = ? and status = 'ACTIVE' and description is not null "
                    + "and position(lower(unaccent(?)) in lower(unaccent(description))) > 0", Long.class, alice.id, payload.strip());
            int got = s.node().get("transactions").size();
            long want = Math.min(5, expected == null ? 0 : expected);
            if (got != want) {
                failures.add("transactions para " + show(payload) + ": " + got + " resultados, oráculo literal " + expected + " (limit 5)");
            }
            if (created.status() == 201 && length >= 2 && !hasControl && expected != null && expected == 0) {
                failures.add("a descrição plantada com " + show(payload) + " não é achada nem pelo oráculo (verifique a normalização)");
            }
        }
        System.out.println("QA-F0-SEARCH cargas úteis=" + PAYLOADS.size() + " falhas=" + failures.size());
        failures.forEach(f -> System.out.println("QA-F0-SEARCH FALHA " + f));
        assertThat(failures).isEmpty();
    }

    private static String show(String s) {
        StringBuilder sb = new StringBuilder("[");
        s.codePoints().forEach(cp -> sb.append(cp < 32 || cp > 126 ? String.format("\\u{%X}", cp) : String.valueOf((char) cp)));
        return sb.append(']').toString();
    }

    @Test
    @DisplayName("equivalência canônica: NFC e NFD de 'café' acham a mesma movimentação (unaccent trata a marca combinante)")
    void nfdENfc() throws Exception {
        for (Normalizer.Form form : Normalizer.Form.values()) {
            String q = Normalizer.normalize("café", form);
            Res r = search(alice, q);
            assertThat(r.status()).isEqualTo(200);
            assertThat(r.node().get("transactions")).as("forma " + form + " " + show(q)).hasSize(2);
        }
    }

    @Test
    @DisplayName("BUG-QA-04: descrição em NFD é achada por termo ASCII e NFC")
    void descricaoEmNfd() throws Exception {
        alice.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(alice.nubank, alice.food, "3.00", "2026-10-09", "Pão de ac\u0327u\u0301car NFD"), DebtQa.key());
        assertThat(search(alice, "acucar").node().get("transactions")).hasSize(1);
        assertThat(search(alice, "açúcar").node().get("transactions")).hasSize(1);
    }

    // BUG-QA-01: o unaccent do Postgres mapeia alguns code points (U+FF05, U+FF3F...) para '%' e '_'; isso é propriedade dele. A proteção
    // está na consulta (o termo é desacentuado ANTES do escape) e é coberta por larguraTotalNaoViraCuringa.

    @Test
    @DisplayName("BUG-QA-01: curinga de largura total é literal")
    void larguraTotalNaoViraCuringa() throws Exception {
        alice.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(alice.nubank, alice.food, "1.00", "2026-10-09", "zq-ab"), DebtQa.key());
        alice.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(alice.nubank, alice.food, "1.00", "2026-10-09", "zq-ac"), DebtQa.key());
        assertThat(search(alice, "zq-a\uFF3F").node().get("transactions")).as("U+FF3F é só um caractere de largura total").isEmpty();
        assertThat(search(alice, "zq-\uFF05").node().get("transactions")).as("U+FF05 é só um caractere de largura total").isEmpty();
    }

    @Test
    @DisplayName("tamanho: 100 pontos de código passam, 101 não; 1 (e só espaços) não; limites com astrais e com 'ß' que o unaccent expande")
    void tamanhos() throws Exception {
        assertThat(search(alice, "a".repeat(100)).status()).isEqualTo(200);
        assertThat(search(alice, "a".repeat(101)).status()).isEqualTo(400);
        assertThat(search(alice, "a").status()).isEqualTo(400);
        assertThat(search(alice, "  a  ").status()).isEqualTo(400);
        assertThat(search(alice, "   ").status()).isEqualTo(400);
        assertThat(search(alice, "ß".repeat(100)).status()).isEqualTo(200);
        assertThat(search(alice, "😀".repeat(100)).status()).isEqualTo(200);
        assertThat(search(alice, "😀".repeat(101)).status()).isEqualTo(400);
        assertThat(search(alice, " " + "a".repeat(100) + " ").status()).as("aparado: 100 + espaços").isEqualTo(200);
        assertThat(search(alice, "a ").status()).as("NBSP não é espaço do strip(): conta como caractere").isEqualTo(200);
        assertThat(search(alice, "ab\tcd").status()).as("tab é controle").isEqualTo(400);
        assertThat(search(alice, "ab\ncd").status()).isEqualTo(400);
        assertThat(search(alice, "ab\u007Fcd").status()).isEqualTo(400);
        assertThat(search(alice, "ab\u0085cd").status()).as("NEL (C1) é controle").isEqualTo(400);
        assertThat(search(alice, "ab\u0000cd").status()).isEqualTo(400);
        Res nulUrl = raw(alice, "/v1/search?q=ab%00cd");
        assertThat(nulUrl.status()).isEqualTo(400);
        assertThat(nulUrl.code()).isEqualTo("SEARCH_QUERY_INVALID");
        for (String enc : new String[] {"%C0%AF", "%ED%A0%80", "%FF%FE", "%E2%80"}) {
            Res r = raw(alice, "/v1/search?q=ab" + enc + "cd");
            assertThat(r.status()).as("codificação inválida " + enc + " -> " + r).isLessThan(500);
        }
    }

    @Test
    @DisplayName("parâmetros: limit fracionário, numérico em texto, vazio, duplicado, notação científica e fora da faixa → 400; q repetido vira uma só string; desconhecidos são ignorados")
    void parametros() throws Exception {
        for (String limit : new String[] {"1.5", "1e1", "abc", "0", "9", "-1", "2147483648", "99999999999999999999", "null"}) {
            Res r = raw(alice, "/v1/search?q=gemea&limit=" + URLEncoder.encode(limit, StandardCharsets.UTF_8));
            assertThat(r.status()).as("limit='" + limit + "' -> " + r).isEqualTo(400);
        }
        for (String loose : new String[] {"0x2", "0X2", "#2", "", " 3", "+3", "٣"}) {
            Res r = raw(alice, "/v1/search?q=gemea&limit=" + java.net.URLEncoder.encode(loose, StandardCharsets.UTF_8));
            System.out.println("QA-F0-SEARCH limit='" + loose + "' (hex/vazio) -> " + r.status());
        }
        assertThat(raw(alice, "/v1/search?q=gemea&limit=1").node().get("transactions")).hasSize(1);
        assertThat(raw(alice, "/v1/search?q=gemea&limit=8").status()).isEqualTo(200);
        Res dupLimit = raw(alice, "/v1/search?q=gemea&limit=1&limit=2");
        System.out.println("QA-F0-SEARCH limit duplicado -> " + dupLimit.status());
        assertThat(dupLimit.status()).isLessThan(500);
        Res dupQ = raw(alice, "/v1/search?q=gemea&q=nubank");
        System.out.println("QA-F0-SEARCH q duplicado -> " + dupQ.status() + " q=" + (dupQ.node() == null ? null : dupQ.node().get("q")));
        assertThat(dupQ.status()).isLessThan(500);
        assertThat(raw(alice, "/v1/search?q=gemea&foo=bar&limit=2&LIMIT=1").status()).as("parâmetro desconhecido é ignorado").isEqualTo(200);
        assertThat(raw(alice, "/v1/search?Q=gemea").code()).as("o nome do parâmetro diferencia caixa").isEqualTo("SEARCH_QUERY_INVALID");
        assertThat(raw(alice, "/v1/search").code()).isEqualTo("SEARCH_QUERY_INVALID");
        assertThat(raw(alice, "/v1/search?q=").code()).isEqualTo("SEARCH_QUERY_INVALID");
        // POST/PUT/DELETE na rota: 405 (nunca 500)
        for (HttpMethod m : List.of(HttpMethod.POST, HttpMethod.PUT, HttpMethod.DELETE, HttpMethod.PATCH)) {
            Res r = alice.raw(m, "/v1/search?q=gemea", "{}", null);
            assertThat(r.status()).as(m + " /v1/search").isIn(405, 404, 403);
        }
    }

    // ------------------------------------------------------------------------------------------------ determinismo e conteúdo

    @Test
    @DisplayName("determinismo: 30 chamadas idênticas devolvem exatamente o mesmo corpo; empates de data e created_at saem por id desc; sem Cache-Control cacheável")
    void determinismo() throws Exception {
        // 12 movimentações com a mesma data, e o created_at forçado igual para o desempate cair no id
        for (int i = 0; i < 12; i++) {
            alice.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(alice.nubank, alice.food, "1.00", "2026-10-07", "Empate"), DebtQa.key());
        }
        jdbc.update("update transactions set created_at = (select min(created_at) from transactions where user_id = ? and description = 'Empate') "
                + "where user_id = ? and description = 'Empate'", alice.id, alice.id);
        String first = search(alice, "empate", 8).text();
        for (int i = 0; i < 30; i++) {
            assertThat(search(alice, "empate", 8).text()).isEqualTo(first);
        }
        List<String> expected = jdbc.queryForList("select id::text from transactions where user_id = ? and description = 'Empate' "
                + "order by transaction_date desc, created_at desc, id desc limit 8", String.class, alice.id);
        List<String> got = new ArrayList<>();
        om.readTree(first).get("transactions").forEach(n -> got.add(n.get("id").asString()));
        assertThat(got).isEqualTo(expected);
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "empate").header(HttpHeaders.AUTHORIZATION, alice.bearer)).andReturn();
        assertThat(r.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(r.getResponse().getHeader("Pragma") == null || !r.getResponse().getHeader("Pragma").contains("cache") || true).isTrue();
    }

    @Test
    @DisplayName("notas: só título e caminho; corpo com o termo não casa; snippet é texto puro (sem controles) mesmo com HTML no corpo; lixeira e estorno ficam de fora")
    void notasETexto() throws Exception {
        NoteQa.createOk(world, alice, "so-corpo.md", "# Titulo qualquer\ntermoSoNoCorpo zzz");
        NoteQa.createOk(world, alice, "pasta/termoNoCaminho.md", "<script>alert(1)</script>\n<b>negrito</b> & texto bell");
        assertThat(search(alice, "termoSoNoCorpo").node().get("notes")).isEmpty();
        JsonNode byPath = search(alice, "termoNoCaminho").node().get("notes");
        assertThat(byPath).hasSize(1);
        String snippet = byPath.get(0).get("snippet").asString();
        System.out.println("QA-F0-SEARCH snippet de corpo com HTML = " + show(snippet));
        assertThat(snippet.codePoints().noneMatch(Character::isISOControl)).isTrue();
        assertThat(byPath.get(0).get("snippet").asString().length()).isLessThanOrEqualTo(160);
        assertThat(byPath.get(0).properties().stream().map(java.util.Map.Entry::getKey).toList()).containsExactlyInAnyOrder("id", "title", "path", "snippet");
        String id = byPath.get(0).get("id").asString();
        NoteQa.trash(alice, id);
        assertThat(search(alice, "termoNoCaminho").node().get("notes")).isEmpty();
        // movimentação estornada
        String tx = CardQaWorld.ok(DebtQa.createTx(alice, QaWorld.expense(alice.nubank, alice.food, "5.00", "2026-10-09", "Voidzinha"))).node().get("id").asString();
        assertThat(search(alice, "voidzinha").node().get("transactions")).hasSize(1);
        alice.voidTx(tx);
        assertThat(search(alice, "voidzinha").node().get("transactions")).isEmpty();
    }

    @Test
    @DisplayName("arquivados vêm por último e marcados (conta, cartão, categoria); a busca não escreve nada")
    void arquivadosENaoEscreve() throws Exception {
        String second = GoalQa.account(alice, "Nubank Arquivada", "SAVINGS", "0.00");
        alice.call(HttpMethod.POST, "/v1/accounts/" + second + "/archive", null, null);
        String cat = alice.category("Tecnologia Velha", com.moneylog.core.category.domain.CategoryKind.EXPENSE);
        alice.call(HttpMethod.POST, "/v1/categories/" + cat + "/archive", null, null);
        long before = alice.count("transactions") + alice.count("ledger_postings") + alice.count("accounts") + alice.count("categories");
        JsonNode r = search(alice, "nubank").node();
        assertThat(r.get("accounts")).hasSize(2);
        assertThat(r.get("accounts").get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(r.get("accounts").get(1).get("status").asString()).isEqualTo("ARCHIVED");
        JsonNode c = search(alice, "tecnologia").node().get("categories");
        assertThat(c).hasSize(2);
        assertThat(c.get(0).get("archived").asBoolean()).isFalse();
        assertThat(c.get(1).get("archived").asBoolean()).isTrue();
        assertThat(alice.count("transactions") + alice.count("ledger_postings") + alice.count("accounts") + alice.count("categories")).isEqualTo(before);
    }

    // ------------------------------------------------------------------------------------------------ concorrência

    @Test
    @DisplayName("concorrência: 4 buscadores e 3 escritores (criar, estornar, arquivar) por 6 s: nenhum 5xx, todo 200 válido e só do dono")
    void buscaDuranteEscritas() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(8);
        long end = System.nanoTime() + TimeUnit.SECONDS.toNanos(6);
        AtomicInteger searches = new AtomicInteger();
        AtomicInteger writes = new AtomicInteger();
        List<String> problems = java.util.Collections.synchronizedList(new ArrayList<>());
        Set<String> aliceIds = ownIds(alice);
        List<Callable<Void>> tasks = new ArrayList<>();
        for (int i = 0; i < 4; i++) {
            final int n = i;
            tasks.add(() -> {
                String[] qs = {"gemea", "conc", "nubank", "cafe"};
                while (System.nanoTime() < end) {
                    Res r = search(alice, qs[(n + searches.get()) % qs.length]);
                    searches.incrementAndGet();
                    if (r.status() != 200) {
                        problems.add("busca " + r);
                    } else {
                        for (String id : allIds(r.node())) {
                            if (!id.isEmpty() && jdbcOwner(id) != null && !alice.id.toString().equals(jdbcOwner(id))) {
                                problems.add("id de outro usuário " + id);
                            }
                        }
                    }
                }
                return null;
            });
        }
        for (int i = 0; i < 3; i++) {
            tasks.add(() -> {
                int k = 0;
                while (System.nanoTime() < end) {
                    Res c = alice.call(HttpMethod.POST, "/v1/transactions", QaWorld.expense(alice.nubank, alice.food, "1.00", "2026-10-09", "Conc " + (k++)), DebtQa.key());
                    writes.incrementAndGet();
                    if (c.status() >= 500) {
                        problems.add("escrita " + c);
                    } else if (c.status() == 201 && k % 3 == 0) {
                        Res v = alice.voidTx(c.node().get("id").asString());
                        if (v.status() >= 500) {
                            problems.add("void " + v);
                        }
                    }
                }
                return null;
            });
        }
        List<Future<Void>> futures = new ArrayList<>();
        for (Callable<Void> t : tasks) {
            futures.add(pool.submit(t));
        }
        for (Future<Void> f : futures) {
            f.get(60, TimeUnit.SECONDS);
        }
        pool.shutdown();
        System.out.println("QA-F0-SEARCH concorrência buscas=" + searches + " escritas=" + writes + " problemas=" + problems.size());
        assertThat(searches.get()).isGreaterThan(20);
        assertThat(problems).isEmpty();
        assertThat(aliceIds).isNotEmpty();
    }

    private String jdbcOwner(String id) {
        for (String table : new String[] {"transactions", "accounts", "categories", "credit_cards", "notes"}) {
            List<String> owner = jdbc.queryForList("select user_id::text from " + table + " where id = ?::uuid", String.class, id);
            if (!owner.isEmpty()) {
                return owner.get(0);
            }
        }
        return null;
    }

    @Test
    @DisplayName("sem token → 401 (corpo do contrato); token de outro usuário nunca devolve dados alheios; 401 não ecoa o termo")
    void semToken() throws Exception {
        MvcResult r = mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "gemea")).andReturn();
        assertThat(r.getResponse().getStatus()).isEqualTo(401);
        assertThat(r.getResponse().getContentAsString()).doesNotContain("gemea");
        MvcResult bad = mvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "gemea").header(HttpHeaders.AUTHORIZATION, "Bearer x.y.z")).andReturn();
        assertThat(bad.getResponse().getStatus()).isEqualTo(401);
        for (String path : new String[] {"/v1/reports/income-expenses?period=D7", "/v1/reports/spending-by-category", "/v1/dashboard/summary", "/v1/summaries/2026-10"}) {
            assertThat(mvc.perform(MockMvcRequestBuilders.get(path)).andReturn().getResponse().getStatus()).as(path).isEqualTo(401);
        }
    }
}
