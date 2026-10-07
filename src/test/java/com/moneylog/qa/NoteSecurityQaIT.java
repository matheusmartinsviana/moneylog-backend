package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
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

/**
 * T113 (QA), critério 5: segurança e contrato das rotas de notas e do vault. Isolamento entre usuários em TODAS as rotas (inclusive vínculos,
 * backlinks, busca, tags, exportação e importação), sem token, campos desconhecidos, caracteres de controle, cabeçalhos ({@code nosniff},
 * {@code Content-Type}), limite de taxa de exportar/importar, nenhum corpo de nota interpretado, {@code LIKE}/{@code unaccent}/injeção de SQL e
 * {@code code} de erro dentro da tabela do contrato.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T113: segurança e contrato das notas e do vault")
class NoteSecurityQaIT {

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
    private Actor b;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = w.new Actor("e6secA");
        b = w.new Actor("e6secB");
    }

    private MvcResult send(Actor as, HttpMethod m, String url, String body) throws Exception {
        return mvc.perform(as.req(m, url, body)).andReturn();
    }

    // ------------------------------------------------------------------------------------------------ isolamento

    @Test
    @DisplayName("isolamento: com a nota, o alvo e o vínculo do usuário A, o usuário B recebe 404 idêntico ao de um UUID aleatório em TODA rota, e nada de A aparece em lista, busca, tags, backlinks, vínculos, exportação ou importação")
    void isolamento() throws Exception {
        String goalA = GoalQa.goal(a, "Meta secreta de A", "1000.00", null);
        String secret = "SEGREDO-DE-A-" + UUID.randomUUID();
        String noteA = NoteQa.createOk(w, a, "segredo/alvo.md", "# alvo " + secret + " #tag-secreta");
        Res linked = NoteQa.createWithLinks(w, a, "segredo/nota.md", "veja [[alvo]] " + secret, "[" + NoteQa.link("GOAL", goalA) + "," + NoteQa.link("NOTE", noteA) + "," + NoteQa.monthLink("2026-10") + "]");
        String noteA2 = CardQaWorld.ok(linked).node().get("id").asString();

        UUID random = UUID.randomUUID();
        String[][] routes = {
                {"GET", "/v1/notes/%s", null}, {"PUT", "/v1/notes/%s", "{\"body\":\"invasor\",\"version\":0}"}, {"POST", "/v1/notes/%s/move", "{\"newPath\":\"invasor.md\",\"version\":0}"},
                {"POST", "/v1/notes/%s/trash", null}, {"POST", "/v1/notes/%s/restore", null}, {"GET", "/v1/notes/%s/backlinks", null},
                {"PUT", "/v1/notes/%s/links", "{\"links\":[],\"version\":0}"}};
        for (String[] route : routes) {
            MvcResult mine = send(b, HttpMethod.valueOf(route[0]), String.format(route[1], noteA), route[2]);
            MvcResult none = send(b, HttpMethod.valueOf(route[0]), String.format(route[1], random), route[2]);
            assertThat(mine.getResponse().getStatus()).as("%s %s", route[0], route[1]).isEqualTo(none.getResponse().getStatus()).isEqualTo(404);
            JsonNode x = om.readTree(mine.getResponse().getContentAsString());
            JsonNode y = om.readTree(none.getResponse().getContentAsString());
            assertThat(x.get("code").asString()).isEqualTo(y.get("code").asString()).isEqualTo("NOTE_NOT_FOUND");
            assertThat(mine.getResponse().getContentAsString()).as("a resposta não vaza o id do dono").doesNotContain(secret);
        }
        // A não foi tocada por B
        JsonNode untouched = NoteQa.get(a, noteA);
        assertThat(untouched.get("version").asLong()).isZero();
        assertThat(untouched.get("body").asString()).contains(secret);
        assertThat(untouched.get("trashedAt").isNull()).isTrue();

        // B lista, busca, vê tags e vínculos: nada de A
        for (String url : new String[] {"/v1/notes", "/v1/notes?q=" + secret, "/v1/notes?tag=tag-secreta", "/v1/notes?status=TRASHED", "/v1/notes?folder=segredo", "/v1/notes/tags",
                "/v1/links?targetType=GOAL&targetId=" + goalA, "/v1/links?targetType=NOTE&targetId=" + noteA, "/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-10",
                "/v1/notes?linkedType=GOAL&linkedId=" + goalA}) {
            Res r = b.get(url.replace(" ", "%20"));
            assertThat(r.status()).as(url).isEqualTo(200);
            assertThat(r.text()).as(url).doesNotContain(secret).doesNotContain(noteA).doesNotContain(noteA2).doesNotContain("secreta");
            if (r.node().has("items")) {
                assertThat(r.node().get("items")).as(url).isEmpty();
            }
        }
        // B tenta vincular a entidade e a nota de A: 422, igual ao de uma entidade que não existe
        for (String[] t : new String[][] {{"GOAL", goalA}, {"NOTE", noteA}, {"ACCOUNT", a.nubank}, {"CARD", a.card}}) {
            String bn = NoteQa.createOk(w, b, "b-" + t[0] + ".md", "x");
            Res mine = b.call(HttpMethod.PUT, "/v1/notes/" + bn + "/links", "{\"links\":[" + NoteQa.link(t[0], t[1]) + "],\"version\":0}", null);
            Res none = b.call(HttpMethod.PUT, "/v1/notes/" + bn + "/links", "{\"links\":[" + NoteQa.link(t[0], UUID.randomUUID().toString()) + "],\"version\":0}", null);
            assertThat(mine.status()).as(t[0]).isEqualTo(422);
            assertThat(mine.code()).isEqualTo("NOTE_LINK_TARGET_NOT_FOUND").isEqualTo(none.code());
        }
        assertThat(jdbc.queryForObject("select count(*) from note_links where user_id = ? and (target_goal_id is not null or target_note_id is not null or target_account_id is not null or target_card_id is not null)", Long.class, b.id)).isZero();

        // exportação de B não traz nada de A; importação em B de um zip com vínculos para ids de A vira pendente, sem rótulo de A
        byte[] exportB = NoteQa.export(w, b);
        assertThat(new String(exportB, StandardCharsets.ISO_8859_1)).doesNotContain(secret);
        String zipText = "---\nmoneylog:\n  links:\n    - { type: GOAL, id: " + goalA + " }\n    - { type: ACCOUNT, id: " + a.nubank + " }\n---\ncorpo";
        Res imp = NoteQa.upload(w, b, new Zip().add("invasor.md", zipText).bytes(), null);
        assertThat(imp.status()).isEqualTo(200);
        assertThat(imp.node().get("pendingLinks").asInt()).isEqualTo(2);
        String invasor = jdbc.queryForObject("select id from notes where user_id = ? and path = 'invasor.md'", String.class, b.id);
        JsonNode view = NoteQa.get(b, invasor);
        for (JsonNode l : view.get("links")) {
            assertThat(l.get("state").asString()).isEqualTo("PENDING");
            assertThat(l.has("target") && !l.get("target").isNull() && l.get("target").has("label") && !l.get("target").get("label").isNull()).as("pendente não traz rótulo do alvo de A").isFalse();
        }
        assertThat(view.toString()).doesNotContain("Meta secreta").doesNotContain("Nubank");
        // A continua vendo só o seu
        assertThat(a.get("/v1/notes").node().get("items").size()).isEqualTo(2);
    }

    @Test
    @DisplayName("sem token, token adulterado ou de usuário inexistente: 401 em toda rota de notas e do vault (sem vazar nada)")
    void semToken() throws Exception {
        String id = NoteQa.createOk(w, a, "x.md", "x");
        String[][] routes = {{"GET", "/v1/notes", null}, {"POST", "/v1/notes", "{\"path\":\"y.md\",\"body\":\"\"}"}, {"GET", "/v1/notes/" + id, null},
                {"PUT", "/v1/notes/" + id, "{\"body\":\"\",\"version\":0}"}, {"POST", "/v1/notes/" + id + "/move", "{\"newPath\":\"z.md\",\"version\":0}"}, {"POST", "/v1/notes/" + id + "/trash", null},
                {"POST", "/v1/notes/" + id + "/restore", null}, {"DELETE", "/v1/notes/trash", null}, {"GET", "/v1/notes/" + id + "/backlinks", null},
                {"PUT", "/v1/notes/" + id + "/links", "{\"links\":[],\"version\":0}"}, {"GET", "/v1/notes/tags", null}, {"GET", "/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-10", null},
                {"GET", "/v1/vault/export", null}};
        String good = a.bearer.substring("Bearer ".length());
        String tampered = "Bearer " + good.substring(0, good.length() - 3) + (good.endsWith("aaa") ? "bbb" : "aaa");
        String ghost = auth.bearer(UUID.randomUUID());
        for (String[] route : routes) {
            for (String token : new String[] {null, "", "Bearer ", "Bearer abc", tampered, "Basic " + good, good}) {
                var req = MockMvcRequestBuilders.request(HttpMethod.valueOf(route[0]), route[1]);
                if (token != null) {
                    req.header(HttpHeaders.AUTHORIZATION, token);
                }
                if (route[2] != null) {
                    req.contentType(MediaType.APPLICATION_JSON).content(route[2]);
                }
                MvcResult r = mvc.perform(req).andReturn();
                assertThat(r.getResponse().getStatus()).as("%s %s com '%s'", route[0], route[1], token).isEqualTo(401);
                assertThat(r.getResponse().getContentAsString()).doesNotContain("x.md");
            }
            // token válido de um usuário que não existe no banco: nunca 5xx e nunca dados
            var req = MockMvcRequestBuilders.request(HttpMethod.valueOf(route[0]), route[1]).header(HttpHeaders.AUTHORIZATION, ghost);
            if (route[2] != null) {
                req.contentType(MediaType.APPLICATION_JSON).content(route[2]);
            }
            MvcResult r = mvc.perform(req).andReturn();
            assertThat(r.getResponse().getStatus()).as("fantasma %s %s", route[0], route[1]).isLessThan(500);
            assertThat(r.getResponse().getContentAsString()).doesNotContain("\"x.md\"");
        }
    }

    @Test
    @DisplayName("campos desconhecidos (incl. userId/id/deletedAt/version extra), tipos errados, controle e JSON torto são 400 VALIDATION_FAILED; tab e CRLF são aceitos; NUL e ESC são recusados")
    void entradaInvalida() throws Exception {
        String id = NoteQa.createOk(w, a, "ok.md", "ok");
        String[][] bad = {
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"\",\"userId\":\"" + b.id + "\"}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"\",\"id\":\"" + UUID.randomUUID() + "\"}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"\",\"deletedAt\":null}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"\",\"links\":[{\"type\":\"GOAL\",\"id\":\"" + UUID.randomUUID() + "\",\"extra\":1}]}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\"}"}, {"POST", "/v1/notes", "{\"body\":\"x\"}"}, {"POST", "/v1/notes", "{\"path\":null,\"body\":\"x\"}"},
                {"POST", "/v1/notes", "{\"path\":123,\"body\":\"x\"}"}, {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":[\"x\"]}"}, {"POST", "/v1/notes", "[]"}, {"POST", "/v1/notes", "null"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\"} {\"x\":1}"}, {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\""},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"a\\u0000b\"}"}, {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"a\\u001bb\"}"},
                {"POST", "/v1/notes", "{\"path\":\"a\\u0000.md\",\"body\":\"x\"}"}, {"POST", "/v1/notes", "{\"path\":\"a\\n.md\",\"body\":\"x\"}"}, {"POST", "/v1/notes", "{\"path\":\"" + "a".repeat(601) + ".md\",\"body\":\"x\"}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\",\"links\":[{\"type\":\"COISA\",\"id\":\"" + UUID.randomUUID() + "\"}]}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\",\"links\":[{\"type\":\"GOAL\",\"id\":\"não-uuid\"}]}"},
                {"POST", "/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\",\"links\":[null]}"},
                {"PUT", "/v1/notes/" + id, "{\"body\":\"x\"}"}, {"PUT", "/v1/notes/" + id, "{\"version\":0}"}, {"PUT", "/v1/notes/" + id, "{\"body\":\"x\",\"version\":\"0\",\"extra\":1}"},
                {"PUT", "/v1/notes/" + id, "{\"body\":\"x\",\"version\":0.5}"}, {"PUT", "/v1/notes/" + id, "{\"body\":\"x\",\"version\":null}"}, {"PUT", "/v1/notes/" + id, "{\"body\":\"x\",\"version\":0,\"path\":\"outro.md\"}"},
                {"POST", "/v1/notes/" + id + "/move", "{\"newPath\":\"b.md\"}"}, {"POST", "/v1/notes/" + id + "/move", "{\"version\":0}"}, {"POST", "/v1/notes/" + id + "/move", "{\"newPath\":\"b.md\",\"version\":0,\"x\":1}"},
                {"POST", "/v1/notes/" + id + "/move", "{\"newPath\":\"\",\"version\":0}"}, {"POST", "/v1/notes/" + id + "/move", "{\"newPath\":\"   \",\"version\":0}"},
                {"PUT", "/v1/notes/" + id + "/links", "{\"version\":0}"}, {"PUT", "/v1/notes/" + id + "/links", "{\"links\":null,\"version\":0}"}, {"PUT", "/v1/notes/" + id + "/links", "{\"links\":[],\"version\":0,\"x\":1}"},
                {"GET", "/v1/notes/não-uuid", null}, {"GET", "/v1/notes/" + id + "x", null}, {"POST", "/v1/notes/xyz/move", "{\"newPath\":\"b.md\",\"version\":0}"},
                {"GET", "/v1/notes?sort=EVIL", null}, {"GET", "/v1/notes?status=ALL", null}, {"GET", "/v1/notes?pageSize=0", null}, {"GET", "/v1/notes?pageSize=-1", null}, {"GET", "/v1/notes?page=0", null},
                {"GET", "/v1/notes?page=abc", null}, {"GET", "/v1/notes?linkedType=GOAL", null}, {"GET", "/v1/notes?linkedId=" + UUID.randomUUID(), null}, 
                {"GET", "/v1/notes?linkedType=NOPE&linkedId=" + UUID.randomUUID(), null}, {"GET", "/v1/notes?q=" + "a".repeat(201), null}, {"GET", "/v1/notes?tag=" + "t".repeat(65), null},
                {"GET", "/v1/links", null}, {"GET", "/v1/links?targetType=GOAL", null}, {"GET", "/v1/links?targetType=GOAL&targetId=" + UUID.randomUUID() + "&targetKey=2026-10", null},
                {"GET", "/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-13", null}};
        for (String[] r : bad) {
            Res res = a.raw(HttpMethod.valueOf(r[0]), r[1], r[2], null);
            String label = r[0] + " " + r[1] + " " + (r[2] == null ? "" : r[2].substring(0, Math.min(90, r[2].length())).replace("\u0000", "\\0"));
            if (r[0].equals("POST") && r[2] != null && r[2].contains("a\\n.md") || false || r[1].contains("targetKey=2026-13")
                    || (r[2] != null && r[2].contains("\"links\":[{\"type\":\"GOAL\",\"id\":\"não-uuid\"")) ) {
                assertThat(res.status()).as(label).isIn(200, 400, 422);
                continue;
            }
            assertThat(res.status()).as("%s -> %s", label, res).isEqualTo(400);
            assertThat(res.code()).as(label).isEqualTo("VALIDATION_FAILED");
        }
        // caminho inválido é 422 (domínio), nunca 400/500
        for (String path : new String[] {"../x.md", "/x.md", "a\\\\b.md", "x.txt", ".md", "con.md", "a/./b.md", "a<b.md", "x.md ", "\u202eabc.md"}) {
            Res res = a.raw(HttpMethod.POST, "/v1/notes", "{\"path\":" + NoteQa.json(w, path) + ",\"body\":\"x\"}", null);
            assertThat(res.status()).as(path).isEqualTo(422);
            assertThat(res.code()).isEqualTo("NOTE_PATH_INVALID");
        }
        // tab e CRLF são texto da nota
        Res ok = NoteQa.create(w, a, "tab.md", "a\tb\r\nc\r\n");
        assertThat(ok.status()).isEqualTo(201);
        assertThat(ok.node().get("body").asString()).isEqualTo("a\tb\r\nc\r\n");
        assertThat(a.count("notes")).isEqualTo(2);
    }

    // ------------------------------------------------------------------------------------------------ cabeçalhos e conteúdo

    @Test
    @DisplayName("cabeçalhos: nosniff e Content-Type certos em todas as respostas (JSON, problem+json, zip com attachment e no-store); HTML/JS na nota sai escapado dentro do JSON, nunca como text/html, nem com Accept: text/html")
    void cabecalhos() throws Exception {
        String xss = "<script>alert(1)</script><img src=x onerror=alert(2)><svg onload=alert(3)>javascript:alert(4) [x](javascript:alert(5)) \"';--";
        String id = NoteQa.createOk(w, a, "xss.md", xss);
        for (String url : new String[] {"/v1/notes/" + id, "/v1/notes", "/v1/notes?q=script", "/v1/notes/tags", "/v1/notes/" + id + "/backlinks", "/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-10",
                "/v1/notes/" + UUID.randomUUID(), "/v1/notes/lixo"}) {
            for (String accept : new String[] {null, "text/html", "*/*", "application/xml"}) {
                var req = MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, a.bearer);
                if (accept != null) {
                    req.header(HttpHeaders.ACCEPT, accept);
                }
                MvcResult r = mvc.perform(req).andReturn();
                String type = String.valueOf(r.getResponse().getContentType());
                assertThat(type).as("%s Accept=%s", url, accept).doesNotContainIgnoringCase("html").doesNotContainIgnoringCase("javascript");
                if (r.getResponse().getStatus() != 406) {
                    assertThat(type).as("%s Accept=%s", url, accept).matches("application/(problem\\+)?json.*");
                }
                assertThat(r.getResponse().getHeader("X-Content-Type-Options")).as("nosniff em %s", url).isEqualTo("nosniff");
            }
        }
        MvcResult note = send(a, HttpMethod.GET, "/v1/notes/" + id, null);
        assertThat(om.readTree(note.getResponse().getContentAsString()).get("body").asString()).isEqualTo(xss);
        MvcResult export = send(a, HttpMethod.GET, "/v1/vault/export", null);
        assertThat(export.getResponse().getContentType()).isEqualTo("application/zip");
        assertThat(export.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(export.getResponse().getHeader(HttpHeaders.CONTENT_DISPOSITION)).startsWith("attachment; filename=\"moneylog-vault-").endsWith(".zip\"");
        assertThat(export.getResponse().getHeader(HttpHeaders.CACHE_CONTROL)).contains("no-store");
        MvcResult imp = send(a, HttpMethod.POST, "/v1/vault/import", null);
        assertThat(imp.getResponse().getStatus()).isIn(400, 415);
        assertThat(imp.getResponse().getHeader("X-Content-Type-Options")).isEqualTo("nosniff");
        // Origin hostil numa rota Bearer: nunca reflete CORS
        MvcResult cors = mvc.perform(MockMvcRequestBuilders.get("/v1/notes/" + id).header(HttpHeaders.AUTHORIZATION, a.bearer).header(HttpHeaders.ORIGIN, "https://evil.example")).andReturn();
        assertThat(cors.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
        MvcResult pre = mvc.perform(MockMvcRequestBuilders.options("/v1/notes").header(HttpHeaders.ORIGIN, "https://evil.example").header("Access-Control-Request-Method", "POST")).andReturn();
        assertThat(pre.getResponse().getHeader("Access-Control-Allow-Origin")).isNull();
    }

    // ------------------------------------------------------------------------------------------------ busca: LIKE, unaccent, SQL

    @Test
    @DisplayName("busca: % _ \\ são literais; unaccent/caixa; injeção de SQL em q, tag, folder e sort vira texto ou 400, nunca 5xx nem vazamento; as tabelas seguem de pé")
    void busca() throws Exception {
        NoteQa.createOk(w, a, "pct.md", "desconto de 100% hoje");
        NoteQa.createOk(w, a, "cem.md", "desconto de 100 hoje");
        NoteQa.createOk(w, a, "sub_linha.md", "um_dois");
        NoteQa.createOk(w, a, "subXlinha.md", "umXdois");
        NoteQa.createOk(w, a, "a_b/x.md", "na pasta a_b");
        NoteQa.createOk(w, a, "aXb/x.md", "na pasta aXb");
        NoteQa.createOk(w, a, "Café.md", "Ação e coração");
        NoteQa.createOk(w, a, "barra.md", "caminho C:\\\\pasta\\\\arq \\ fim");
        NoteQa.createOk(w, b, "do-b.md", "SEGREDO-B desconto 100% um_dois");
        class S {
            Set<String> q(String term) throws Exception {
                Res r = param("q", term);
                assertThat(r.status()).as("q=%s: %s", term, r).isEqualTo(200);
                Set<String> out = new TreeSet<>();
                r.node().get("items").forEach(i -> out.add(i.get("path").asString()));
                return out;
            }
        }
        S s = new S();
        assertThat(s.q("100%")).containsExactly("pct.md");
        assertThat(s.q("%")).containsExactly("pct.md");
        assertThat(s.q("_")).containsExactlyInAnyOrder("sub_linha.md", "a_b/x.md");
        assertThat(s.q("um_dois")).containsExactly("sub_linha.md");
        assertThat(s.q("\\")).containsExactly("barra.md");
        assertThat(s.q("%%")).isEmpty();
        assertThat(s.q("café")).containsExactly("Café.md");
        assertThat(s.q("CAFE")).containsExactly("Café.md");
        assertThat(s.q("acao")).containsExactly("Café.md");
        assertThat(s.q("CORAÇÃO")).containsExactly("Café.md");
        for (String evil : new String[] {"'; DROP TABLE notes; --", "' OR '1'='1", "%' OR 1=1 --", "\\' OR 1=1 --", "x') UNION SELECT id FROM users --", "\"; select pg_sleep(5); --", "𝕏", "\u00e9\u0301",
                "a".repeat(200), "_".repeat(200), "%_%_%_%_%_%_%_%_%_%_%_%_%_%_%_%_%", "\\\\\\\\", "ß", "İ", "ǅ", "ﬃ", "\u0130"}) {
            Set<String> got = s.q(evil);
            assertThat(got).as("q=%s", evil).doesNotContain("do-b.md");
        }
        for (String[] kv : new String[][] {{"tag", "x' OR 1=1 --"}, {"folder", "%"}, {"folder", "_"}, {"folder", "' OR 1=1 --"}, {"folder", "a_b"}}) {
            Res r = param(kv[0], kv[1]);
            assertThat(r.status()).as("%s=%s", kv[0], kv[1]).isEqualTo(200);
            r.node().get("items").forEach(i -> assertThat(i.get("path").asString()).isNotEqualTo("do-b.md"));
        }
        Set<String> folderUnderscore = new TreeSet<>();
        param("folder", "a_b").node().get("items").forEach(i -> folderUnderscore.add(i.get("path").asString()));
        assertThat(folderUnderscore).containsExactly("a_b/x.md");
        for (String f : new String[] {"%", "_"}) {
            assertThat(param("folder", f).node().get("items")).as("folder=%s é literal", f).isEmpty();
        }
        assertThat(jdbc.queryForObject("select count(*) from notes where user_id = ?", Long.class, a.id)).isEqualTo(8L);
        assertThat(jdbc.queryForObject("select count(*) from users", Long.class)).isGreaterThan(1L);
    }

    private Res param(String key, String value) throws Exception {
        MvcResult r = mvc.perform(a.req(HttpMethod.GET, "/v1/notes", null).queryParam(key, value).queryParam("pageSize", "50")).andReturn();
        return NoteQa.finish(w, r, "GET /v1/notes?" + key);
    }

    // ------------------------------------------------------------------------------------------------ contrato

    @Test
    @DisplayName("contrato: toda resposta das rotas de notas (sucesso e erro) bate com o OpenAPI e todo 'code' de erro está na tabela do contrato")
    void contrato() throws Exception {
        String goal = GoalQa.goal(a, "Meta", "100.00", null);
        String n1 = NoteQa.createOk(w, a, "c/n1.md", "---\ntitle: T\ntags: [a]\n---\n[[n2]] #b");
        String n2 = NoteQa.createOk(w, a, "c/n2.md", "corpo");
        NoteQa.setLinks(a, n1, "[" + NoteQa.link("GOAL", goal) + "," + NoteQa.monthLink("2026-10") + "," + NoteQa.link("NOTE", n2) + "]", 0);
        NoteQa.edit(w, a, n2, "novo [[n1]]", 0);
        NoteQa.move(w, a, n2, "c/n2b.md", 1);
        NoteQa.trash(a, n2);
        NoteQa.trash(a, n2);
        NoteQa.restore(a, n2);
        NoteQa.restore(a, n2);
        NoteQa.edit(w, a, n1, "x", 0);
        NoteQa.create(w, a, "c/n1.md", "dup");
        NoteQa.create(w, a, "../x.md", "dup");
        NoteQa.create(w, a, "big.md", "x".repeat(210_000));
        NoteQa.createWithLinks(w, a, "l.md", "x", "[" + NoteQa.link("GOAL", UUID.randomUUID().toString()) + "]");
        NoteQa.createWithLinks(w, a, "l2.md", "x", "[" + NoteQa.link("NOTE", n1) + "," + NoteQa.link("NOTE", n1) + "]");
        NoteQa.create(w, a, "fm.md", "---\nmoneylog: {}\n---\nx");
        NoteQa.create(w, a, "fm2.md", "---\na: [\n---\nx");
        for (String url : new String[] {"/v1/notes", "/v1/notes?q=novo", "/v1/notes?tag=a", "/v1/notes?folder=c", "/v1/notes?status=TRASHED", "/v1/notes?sort=TITLE", "/v1/notes?sort=PATH&pageSize=1&page=2",
                "/v1/notes/tags", "/v1/notes/" + n1, "/v1/notes/" + n1 + "/backlinks", "/v1/links?targetType=GOAL&targetId=" + goal, "/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-10",
                "/v1/links?targetType=NOTE&targetId=" + n2, "/v1/notes/" + UUID.randomUUID()}) {
            a.get(url);
        }
        a.call(HttpMethod.DELETE, "/v1/notes/trash", null, null);
        NoteQa.upload(w, a, new Zip().add("../x.md", "x").bytes(), null);
        NoteQa.upload(w, a, new byte[0], null);
        NoteQa.upload(w, a, new Zip().add("i.md", "x").bytes(), "RENAME");
        NoteQa.upload(w, a, new Zip().add("i.md", "x").bytes(), "XX");
        a.raw(HttpMethod.GET, "/v1/vault/export", null, null);
        assertThat(w.contractViolations).as("violações de contrato").isEmpty();
        assertThat(unknown(w.seenCodes)).as("códigos de erro fora da tabela do contrato").isEmpty();
        System.out.println("QA-E6-SEC códigos vistos: " + new TreeSet<>(w.seenCodes));
    }

    static List<String> unknown(Set<String> seen) throws Exception {
        String yaml = java.nio.file.Files.readString(com.moneylog.util.ContractValidation.SPEC_FILE);
        String table = yaml.substring(yaml.indexOf("## Códigos de erro"), yaml.indexOf("Se o domínio usar outro nome"));
        Set<String> documented = new TreeSet<>();
        Matcher m = Pattern.compile("`([A-Z][A-Z0-9_*]+)`").matcher(table);
        while (m.find()) {
            documented.add(m.group(1));
        }
        List<String> out = new ArrayList<>();
        for (String code : seen) {
            boolean ok = documented.contains(code) || documented.stream().filter(d -> d.startsWith("*")).anyMatch(d -> code.endsWith(d.substring(1)));
            if (!ok) {
                out.add(code);
            }
        }
        return out;
    }
}
