package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.zip.ZipEntry;
import java.util.zip.ZipInputStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

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
@Tag("slow")
@DisplayName("QA T113: limites e desempenho do vault")
class NoteVolumeQaIT {

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
    private com.moneylog.infra.note.persistence.NoteRepositoryJpa noteRepository;
    @Autowired
    private org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate named;
    @Autowired
    private com.moneylog.core.sk.id.IdGenerator idGenerator;
    @Autowired
    private com.moneylog.core.sk.time.TimeProvider timeProvider;

    private CardQaWorld w;
    private Actor a;

    @BeforeEach
    void cenario() throws Exception {
        clock.set(MutableClockConfig.INITIAL);
        w = new CardQaWorld(mvc, om, jdbc, auth, tm, clock);
        a = w.new Actor("e6vol");
    }

    private void committed(Runnable work) {
        new TransactionTemplate(tm).executeWithoutResult(s -> work.run());
    }

    private static long ms(long startNanos) {
        return (System.nanoTime() - startNanos) / 1_000_000;
    }

    private static boolean hasLoneSurrogate(String s) {
        for (int i = 0; i < s.length(); i++) {
            char c = s.charAt(i);
            if (Character.isHighSurrogate(c) && i + 1 < s.length() && Character.isLowSurrogate(s.charAt(i + 1))) {
                i++;
            } else if (Character.isSurrogate(c)) {
                return true;
            }
        }
        return false;
    }

    @Test
    @DisplayName("200 KB em bytes UTF-8: 204.800 bytes entra (ASCII, 2 bytes e 4 bytes por caractere) e 204.801 é 422 NOTE_TOO_LARGE, na criação e na edição; o banco recusa acima do limite")
    void duzentosKb() throws Exception {
        String ascii = "x".repeat(204_800);
        Res ok = NoteQa.create(w, a, "ascii.md", ascii);
        assertThat(ok.status()).as("%s", ok).isEqualTo(201);
        assertThat(NoteQa.create(w, a, "ascii2.md", ascii + "x").code()).isEqualTo("NOTE_TOO_LARGE");
        assertThat(NoteQa.create(w, a, "acento.md", "é".repeat(102_400)).status()).isEqualTo(201);
        Res big2 = NoteQa.create(w, a, "acento2.md", "é".repeat(102_400) + "x");
        assertThat(big2.status()).isEqualTo(422);
        assertThat(big2.code()).isEqualTo("NOTE_TOO_LARGE");
        assertThat(NoteQa.create(w, a, "emoji.md", "😀".repeat(51_200)).status()).isEqualTo(201);
        assertThat(NoteQa.create(w, a, "emoji2.md", "😀".repeat(51_200) + "x").code()).isEqualTo("NOTE_TOO_LARGE");
        String id = ok.node().get("id").asString();
        Res edit = NoteQa.edit(w, a, id, ascii + "x", 0);
        assertThat(edit.status()).isEqualTo(422);
        assertThat(edit.code()).isEqualTo("NOTE_TOO_LARGE");
        assertThat(NoteQa.get(a, id).get("version").asLong()).isZero();
        assertThatThrowsOnInsert();
        assertThat(NoteQa.create(w, a, "crlf.md", "---\r\ntitle: x\r\n---\r\n" + "linha\r\n".repeat(20_000)).status()).isEqualTo(201);
        assertThat(w.contractViolations).isEmpty();
    }

    private void assertThatThrowsOnInsert() {
        org.assertj.core.api.Assertions.assertThatThrownBy(() -> committed(() -> jdbc.update("insert into notes (id,user_id,path,path_key,title,body,created_at,updated_at,version) values (?,?,'g.md','g.md','g',?,now(),now(),0)",
                UUID.randomUUID(), a.id, "x".repeat(204_801)))).hasMessageContaining("ck_notes_body_size");
    }

    @Test
    @DisplayName("500 wikilinks distintos por nota: o 500º link é indexado (backlink e renomear reescrevem) e a nota com 501 é recusada (422 NOTE_LINK_LIMIT_REACHED): nenhum link fica sem índice")
    void quinhentosWikilinks() throws Exception {
        StringBuilder body = new StringBuilder();
        for (int i = 0; i < 499; i++) {
            body.append("[[pendente").append(i).append("]] ");
        }
        Res over = NoteQa.create(w, a, "excesso.md", body + "[[Alvo Tardio]] [[Um a mais]]");
        assertThat(over.status()).as("%s", over).isEqualTo(422);
        assertThat(over.code()).isEqualTo("NOTE_LINK_LIMIT_REACHED");
        assertThat(jdbc.queryForObject("select count(*) from notes where user_id = ? and path = 'excesso.md'", Long.class, a.id)).isZero();
        body.append("[[Alvo Tardio]]");
        String source = NoteQa.createOk(w, a, "fonte.md", body.toString());
        String target = NoteQa.createOk(w, a, "Alvo Tardio.md", "alvo");
        assertThat(jdbc.queryForObject("select count(*) from note_links where note_id = ?::uuid and origin = 'WIKILINK'", Long.class, source)).isEqualTo(500L);
        List<String> backlinks = new ArrayList<>();
        a.getOk("/v1/notes/" + target + "/backlinks").get("items").forEach(i -> backlinks.add(i.get("path").asString()));
        System.out.println("QA-E6-LIM link 501 ([[Alvo Tardio]]): backlinks do alvo = " + backlinks);
        Res moved = NoteQa.move(w, a, target, "Alvo Renomeado.md", 0);
        assertThat(moved.status()).isEqualTo(200);
        String text = NoteQa.get(a, source).get("body").asString();
        System.out.println("QA-E6-LIM depois de renomear o alvo, o texto da fonte termina com: ..." + text.substring(text.length() - 40));
        assertThat(backlinks).as("a nota fonte cita [[Alvo Tardio]] e deveria ser backlink").containsExactly("fonte.md");
        assertThat(text).as("o link 501 deveria ter sido reescrito para [[Alvo Renomeado]]").endsWith("[[Alvo Renomeado]]");
    }

    @Test
    @DisplayName("100 vínculos explícitos por nota: 100 passam, 101 é recusado (400/422) sem 5xx; duplicatas contam uma vez; 100 tags: o excedente é cortado sem 5xx; tag e título enormes não derrubam")
    void cemVinculosECemTags() throws Exception {
        List<String> months = new ArrayList<>();
        for (int y = 2000; y <= 2100 && months.size() < 101; y++) {
            for (int m = 1; m <= 12 && months.size() < 101; m++) {
                months.add(NoteQa.monthLink(String.format("%d-%02d", y, m)));
            }
        }
        String id = NoteQa.createOk(w, a, "v.md", "x");
        Res hundred = NoteQa.setLinks(a, id, "[" + String.join(",", months.subList(0, 100)) + "]", 0);
        assertThat(hundred.status()).as("%s", hundred).isEqualTo(200);
        Res over = a.raw(HttpMethod.PUT, "/v1/notes/" + id + "/links", "{\"links\":[" + String.join(",", months) + "],\"version\":1}", null);
        assertThat(over.status()).as("%s", over).isIn(400, 422);
        List<String> dup = new ArrayList<>(months.subList(0, 100));
        dup.addAll(months.subList(0, 100));
        Res dups = a.raw(HttpMethod.PUT, "/v1/notes/" + id + "/links", "{\"links\":[" + String.join(",", dup) + "],\"version\":1}", null);
        System.out.println("QA-E6-LIM 200 vínculos (100 repetidos): " + dups.status() + " " + dups.code());
        assertThat(dups.status()).isLessThan(500);
        assertThat(jdbc.queryForObject("select count(*) from note_links where note_id = ?::uuid and origin = 'EXPLICIT'", Long.class, id)).isLessThanOrEqualTo(100L);

        StringBuilder tags = new StringBuilder();
        for (int i = 0; i < 150; i++) {
            tags.append("#tag").append(i).append(' ');
        }
        Res manyTags = NoteQa.create(w, a, "tags.md", tags.toString());
        System.out.println("QA-E6-LIM 150 tags: " + manyTags.status() + " " + manyTags.code() + (manyTags.status() == 201 ? " guardadas=" + manyTags.node().get("tags").size() : ""));
        assertThat(manyTags.status()).isLessThan(500);
        Res longTag = NoteQa.create(w, a, "tag-longa.md", "#" + "a".repeat(100) + " texto");
        System.out.println("QA-E6-LIM tag de 100 caracteres: " + longTag.status() + " " + longTag.code());
        assertThat(longTag.status()).as("%s", longTag).isLessThan(500);
        Res fmTag = NoteQa.create(w, a, "fm-tag.md", "---\ntags: [" + "b".repeat(100) + ", \"" + "é".repeat(70) + "\"]\n---\ncorpo");
        assertThat(fmTag.status()).as("%s", fmTag).isLessThan(500);
        Res longTitle = NoteQa.create(w, a, "titulo.md", "---\ntitle: " + "t".repeat(300) + "\n---\ncorpo");
        assertThat(longTitle.status()).as("%s", longTitle).isLessThan(500);
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("BUG-QA-10: título cortado no meio de um emoji")
    void tituloCortadoNoEmoji() throws Exception {
        Res splitTitle = NoteQa.create(w, a, "titulo-emoji.md", "---\ntitle: " + "a".repeat(254) + "😀bb\n---\ncorpo");
        System.out.println("QA-E6-LIM título com emoji cortado em 255: " + splitTitle.status() + " " + splitTitle.code());
        assertThat(splitTitle.status()).as("%s", splitTitle).isLessThan(500);
        if (splitTitle.status() == 201) {
            String title = splitTitle.node().get("title").asString();
            assertThat(hasLoneSurrogate(title)).as("o título cortado ficou com um par substituto pela metade").isFalse();
            String stored = jdbc.queryForObject("select title from notes where id = ?::uuid", String.class, splitTitle.node().get("id").asString());
            assertThat(stored).as("título gravado: %s", stored.substring(Math.max(0, stored.length() - 4))).doesNotContain("?");
        }
        assertThat(w.contractViolations).isEmpty();
    }

    @Test
    @DisplayName("trechos de busca e de backlink (160 caracteres) cortados no meio de um emoji: a resposta não pode ter substituto solto")
    void trechoNoMeioDoEmoji() throws Exception {
        String target = NoteQa.createOk(w, a, "alvo.md", "alvo");
        for (int pad = 150; pad < 170; pad++) {
            String body = "x".repeat(pad) + "😀😀😀😀 [[alvo]] termo-raro-" + pad + " " + "😀".repeat(80);
            NoteQa.createOk(w, a, "e" + pad + ".md", body);
        }
        List<String> broken = new ArrayList<>();
        JsonNode backlinks = a.getOk("/v1/notes/" + target + "/backlinks?pageSize=50");
        for (JsonNode b : backlinks.get("items")) {
            if (hasLoneSurrogate(b.get("context").asString())) {
                broken.add("backlink " + b.get("path").asString());
            }
        }
        JsonNode found = a.getOk("/v1/notes?q=termo-raro&pageSize=50");
        for (JsonNode n : found.get("items")) {
            if (n.has("snippet") && !n.get("snippet").isNull() && hasLoneSurrogate(n.get("snippet").asString())) {
                broken.add("snippet " + n.get("path").asString());
            }
        }
        System.out.println("QA-E6-LIM trechos com substituto solto: " + broken.size() + " " + broken.subList(0, Math.min(5, broken.size())));
        assertThat(broken).isEmpty();
    }

    private void seedNotes(String prefix, int count, String bodySql) {
        committed(() -> jdbc.update("insert into notes (id,user_id,path,path_key,title,body,created_at,updated_at,version) select gen_random_uuid(), ?, ? || g || '.md', ? || g || '.md', ? || g, "
                + bodySql + ", now(), now(), 0 from generate_series(1, ?) g", a.id, prefix, prefix, prefix, count));
    }

    private String seedTarget(String path) throws Exception {
        return NoteQa.createOk(w, a, path, "alvo");
    }

    private void seedLinkers(int count, String targetId, String targetText) {
        seedNotes("lk/", count, "'[[" + targetText + "]] texto de enchimento ' || g");
        committed(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_note_id,target_text,target_name_key,embed,context,created_at,updated_at) "
                + "select gen_random_uuid(), user_id, id, 0, 'WIKILINK', 'NOTE', ?::uuid, ?, lower(?), false, 'ctx', now(), now() from notes where user_id = ? and path like 'lk/%'", targetId, targetText, targetText, a.id));
    }

    @Test
    @DisplayName("desempenho do rename: 500, 2.000 e 4.900 notas linkando a mesma nota (todas reescritas em uma transação); tempo registrado")
    void desempenhoRenomear() throws Exception {
        String target = seedTarget("Alvo Grande.md");
        long[] sizes = {500, 2000, 4900};
        long previous = 0;
        long version = 0;
        String path = "Alvo Grande";
        for (long size : sizes) {
            committed(() -> jdbc.update("delete from notes where user_id = ? and path like 'lk/%'", a.id));
            seedLinkers((int) size, target, path);
            String next = path.equals("Alvo Grande") ? "Alvo Médio" : "Alvo Grande";
            long t = System.nanoTime();
            Res r = NoteQa.move(w, a, target, next + ".md", version);
            long took = ms(t);
            System.out.println("QA-E6-PERF rename com " + size + " notas linkando: " + took + " ms, status " + r.status() + ", reescritas=" + (r.status() == 200 ? r.node().get("rewrittenNotes").size() : -1));
            assertThat(r.status()).as("%s", r).isEqualTo(200);
            assertThat(r.node().get("rewrittenNotes").size()).isEqualTo((int) size);
            assertThat(took).as("rename com %d notas", size).isLessThan(120_000);
            path = next;
            version = r.node().get("note").get("version").asLong();
            previous = took;
        }
    }

    @Test
    @DisplayName("desempenho da busca: 5.000 notas de ~4 KB e 300 notas de 200 KB; busca por termo raro, comum, com acento, sem resultado e por lista; tempos registrados")
    void desempenhoBusca() throws Exception {
        seedNotes("b/", 5000, "'# Nota ' || g || E'\\n' || repeat('Texto com acentuação e coração, açúcar e ÜNICODE. ', 80) || 'termo-raro-' || (g % 50)");
        seedNotes("g/", 300, "repeat(md5(g::text) || ' ação ', 4800) || 'fim-g'");
        assertThat(a.count("notes")).isEqualTo(5300L);
        for (String q : new String[] {"termo-raro-7", "coração", "ACUCAR", "nao-existe-xyz", "fim-g", "ação", "%", "a"}) {
            long t = System.nanoTime();
            Res r = w.res(mvc.perform(a.req(HttpMethod.GET, "/v1/notes", null).queryParam("q", q).queryParam("pageSize", "50")).andReturn());
            System.out.println("QA-E6-PERF busca q=" + q + ": " + ms(t) + " ms, status " + r.status() + ", itens=" + (r.status() == 200 ? r.node().get("items").size() : -1));
            assertThat(r.status()).isEqualTo(200);
            assertThat(ms(t)).as("busca q=%s", q).isLessThan(60_000);
        }
        com.moneylog.infra.note.gateways.NoteGatewayImpl strict = new com.moneylog.infra.note.gateways.NoteGatewayImpl(noteRepository, named, idGenerator, timeProvider, 1);
        long probe = System.nanoTime();
        a.getOk("/v1/notes?q=a&pageSize=50");
        long wide = ms(probe);
        if (wide > 2_000) {
            org.assertj.core.api.Assertions.assertThatThrownBy(() -> strict.search(new com.moneylog.core.note.app.NoteQuery(a.id, "a", null, null, null, null, null, null, null,
                    new com.moneylog.core.sk.page.PageQuery(1, 50)))).isInstanceOf(com.moneylog.core.note.exception.NoteExceptions.NoteSearchTimeoutException.class);
        }
        long t = System.nanoTime();
        a.getOk("/v1/notes?pageSize=50&sort=TITLE");
        System.out.println("QA-E6-PERF lista sem busca (sort=TITLE): " + ms(t) + " ms");
        t = System.nanoTime();
        a.getOk("/v1/notes/tags");
        System.out.println("QA-E6-PERF tags: " + ms(t) + " ms");
    }

    @Test
    @DisplayName("desempenho no limite de links gravados: 1.000 notas x 500 wikilinks (500 mil linhas): criar, editar, mandar para a lixeira e restaurar uma nota; tempo registrado")
    void desempenhoComMuitosLinks() throws Exception {
        seedNotes("m/", 1000, "'corpo '");
        committed(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_note_id,target_text,target_name_key,embed,context,created_at,updated_at) "
                + "select gen_random_uuid(), n.user_id, n.id, p, 'WIKILINK', 'NOTE', null, 'pendente ' || p, 'pendente ' || p, false, 'ctx', now(), now() from notes n cross join generate_series(0, 499) p where n.user_id = ?", a.id));
        System.out.println("QA-E6-PERF linhas de link: " + a.count("note_links"));
        long t = System.nanoTime();
        Res c = NoteQa.create(w, a, "nova.md", "[[pendente 1]]");
        long create = ms(t);
        t = System.nanoTime();
        Res tr = NoteQa.trash(a, c.node().get("id").asString());
        long trash = ms(t);
        t = System.nanoTime();
        Res rs = NoteQa.restore(a, c.node().get("id").asString());
        long restore = ms(t);
        System.out.println("QA-E6-PERF com 500 mil links gravados: criar=" + create + " ms, lixeira=" + trash + " ms, restaurar=" + restore + " ms");
        assertThat(c.status()).isEqualTo(201);
        assertThat(tr.status()).isEqualTo(200);
        assertThat(rs.status()).isEqualTo(200);
        assertThat(create).as("criar com 500 mil links gravados").isLessThan(3_000);
        assertThat(trash).as("lixeira com 500 mil links gravados").isLessThan(3_000);
        assertThat(restore).as("restaurar com 500 mil links gravados").isLessThan(3_000);
    }

    @Test
    @DisplayName("S-278: wikilinks gravados antes da chave de nome (target_name_key nula) entram no recálculo seguinte, resolvem e ganham a chave")
    void linksAnterioresAChave() throws Exception {
        String reader = NoteQa.createOk(w, a, "leitor.md", "texto");
        committed(() -> jdbc.update("insert into note_links (id,user_id,note_id,position,origin,target_type,target_note_id,target_text,embed,context,created_at,updated_at) "
                + "values (gen_random_uuid(), ?, ?::uuid, 0, 'WIKILINK', 'NOTE', null, 'Antigo Alvo', false, 'ctx', now(), now())", a.id, reader));
        String target = NoteQa.createOk(w, a, "pasta/Outro Nome.md", "x");
        assertThat(jdbc.queryForObject("select target_note_id from note_links where note_id = ?::uuid and target_text = 'Antigo Alvo'", String.class, reader)).isNull();
        assertThat(jdbc.queryForObject("select target_name_key from note_links where note_id = ?::uuid and target_text = 'Antigo Alvo'", String.class, reader))
                .as("a chave foi preenchida no recálculo").isEqualTo("antigo alvo");
        String alvo = NoteQa.createOk(w, a, "Antigo Alvo.md", "alvo");
        assertThat(jdbc.queryForObject("select target_note_id::text from note_links where note_id = ?::uuid and target_text = 'Antigo Alvo'", String.class, reader)).isEqualTo(alvo);
        assertThat(target).isNotNull();
    }

    @Test
    @DisplayName("BUG-QA-11: o zip exportado de 5.000 notas precisa poder ser reimportado")
    void reimportar5000() throws Exception {
        seedNotes("n/", 5000, "'# Nota ' || g || E'\\n' || repeat('texto ', 100)");
        byte[] export = NoteQa.export(w, a);
        long t = System.nanoTime();
        Actor other = w.new Actor("e6vol2");
        t = System.nanoTime();
        Res imp = NoteQa.upload(w, other, export, "SKIP");
        System.out.println("QA-E6-PERF importar o zip de 5.000 notas: " + ms(t) + " ms -> " + imp.status() + " " + imp.code());
        assertThat(imp.status()).as("o vault de 5.000 notas (dentro do limite) exportado não pode ser reimportado? %s", imp).isEqualTo(200);

    }

    @Test
    @DisplayName("exportar 5.000 notas e importar de volta: o zip exportado de um vault válido (<= 5.000 notas) precisa poder ser importado; o limite de 2.000 entradas quebra o round-trip. Importar 2.000 notas com wikilinks: tempo registrado")
    void exportar5000() throws Exception {
        seedNotes("n/", 5000, "'# Nota ' || g || E'\\n' || repeat('texto ', 100) || ' [[n/' || (g % 5000 + 1) || ']] #tag' || (g % 20)");
        long t = System.nanoTime();
        byte[] export = NoteQa.export(w, a);
        long exportMs = ms(t);
        int entries = 0;
        try (ZipInputStream zip = new ZipInputStream(new ByteArrayInputStream(export), StandardCharsets.UTF_8)) {
            ZipEntry e;
            while ((e = zip.getNextEntry()) != null) {
                entries++;
            }
        }
        System.out.println("QA-E6-PERF exportar 5.000 notas: " + exportMs + " ms, " + export.length + " bytes, " + entries + " entradas");
        assertThat(entries).isEqualTo(5000);
        Zip big = new Zip();
        for (int i = 0; i < 2000; i++) {
            big.add("i/n" + i + ".md", "# Nota " + i + "\nlink [[n" + ((i + 1) % 2000) + "]] e [[i/n" + ((i + 7) % 2000) + "|alias]] #tag" + (i % 20) + "\n" + "texto ".repeat(150));
        }
        Actor third = w.new Actor("e6vol3");
        t = System.nanoTime();
        Res imp2 = NoteQa.upload(w, third, big.bytes(), "SKIP");
        System.out.println("QA-E6-PERF importar 2.000 notas com wikilinks: " + ms(t) + " ms -> " + imp2.status());
        assertThat(imp2.status()).isEqualTo(200);
        assertThat(imp2.node().get("created").asInt()).isEqualTo(2000);
    }
}
