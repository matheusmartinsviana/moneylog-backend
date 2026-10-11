package com.moneylog.infra.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doAnswer;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.note.app.gateways.NoteGateway;
import com.moneylog.util.ContractValidation;

@DisplayName("Notas: renomear e mover com reescrita de wikilinks (T110)")
class NoteMoveIT extends NotesItBase {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    @MockitoSpyBean
    private NoteGateway noteGateway;

    @AfterEach
    void limparEspiao() {
        Mockito.reset(noteGateway);
    }

    @Test
    @DisplayName("CA1: renomear reviews/2026-10.md para reviews/outubro.md reescreve [[2026-10]], [[reviews/2026-10]] e [[2026-10|alias]] (preserva alias e seção) e rewrittenNotes lista as notas mudadas")
    void deveReescreverOsLinks() throws Exception {
        JsonNode review = createNote("reviews/2026-10.md", "# Outubro");
        JsonNode a = createNote("a.md", "[[2026-10]], [[reviews/2026-10]] e [[2026-10|alias]] e [[2026-10#Resumo]] e ![[2026-10]]");
        JsonNode b = createNote("pasta/b.md", "sem link [[outra]]");
        JsonNode moved = okBody(post("/v1/notes/" + review.get("id").asString() + "/move", "{\"newPath\":\"reviews/outubro.md\",\"version\":0}"));
        assertThat(moved.get("note").get("path").asString()).isEqualTo("reviews/outubro.md");
        assertThat(moved.get("note").get("title").asString()).isEqualTo("outubro");
        assertThat(moved.get("note").get("version").asLong()).isEqualTo(1);
        assertThat(moved.get("rewrittenNotes").size()).isEqualTo(1);
        assertThat(moved.get("rewrittenNotes").get(0).get("path").asString()).isEqualTo("a.md");
        assertThat(getNote(a.get("id").asString()).get("body").asString()).isEqualTo("[[outubro]], [[reviews/outubro]] e [[outubro|alias]] e [[outubro#Resumo]] e ![[outubro]]");
        assertThat(getNote(b.get("id").asString()).get("body").asString()).isEqualTo("sem link [[outra]]");
        assertThat(getNote(b.get("id").asString()).get("version").asLong()).isZero();
        JsonNode links = getNote(a.get("id").asString()).get("links");
        for (JsonNode link : links) {
            assertThat(link.get("state").asString()).isEqualTo("RESOLVED");
            assertThat(link.get("target").get("id").asString()).isEqualTo(review.get("id").asString());
        }
        assertThat(backlinks(review.get("id").asString()).get("items").size()).isEqualTo(1);
    }

    @Test
    @DisplayName("CA2: com o mesmo nome em outra pasta, só reescreve os links que apontavam para ESTA nota")
    void deveReescreverSoOsLinksDaquelaNota() throws Exception {
        JsonNode raiz = createNote("Nota.md", "raiz");
        JsonNode fundo = createNote("a/b/Nota.md", "fundo");
        JsonNode leitor = createNote("leitor.md", "[[Nota]] e [[a/b/Nota]] e [[b/Nota]]");
        moveNote(fundo.get("id").asString(), "a/b/Renomeada.md", 0);
        JsonNode after = getNote(leitor.get("id").asString());
        assertThat(after.get("body").asString()).isEqualTo("[[Nota]] e [[a/b/Renomeada]] e [[a/b/Renomeada]]");
        assertThat(after.get("links").get(0).get("target").get("id").asString()).isEqualTo(raiz.get("id").asString());
        assertThat(after.get("links").get(1).get("target").get("id").asString()).isEqualTo(fundo.get("id").asString());
    }

    @Test
    @DisplayName("CA3: falha na 3ª nota desfaz tudo: nenhuma nota reescrita, o caminho e a versão da movida não mudam (rollback real)")
    void deveFazerRollbackTotal() throws Exception {
        JsonNode alvo = createNote("alvo.md", "x");
        JsonNode n1 = createNote("n1.md", "[[alvo]] um");
        JsonNode n2 = createNote("n2.md", "[[alvo]] dois");
        JsonNode n3 = createNote("n3.md", "[[alvo]] tres");
        JsonNode n4 = createNote("n4.md", "[[alvo]] quatro");
        AtomicInteger saves = new AtomicInteger();
        doAnswer(invocation -> {
            if (saves.incrementAndGet() == 3) {
                throw new IllegalStateException("falha injetada na 3ª gravação");
            }
            return invocation.callRealMethod();
        }).when(noteGateway).save(any());

        mockMvc.perform(post("/v1/notes/" + alvo.get("id").asString() + "/move", "{\"newPath\":\"novo.md\",\"version\":0}")).andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code").value("INTERNAL_ERROR")).andExpect(ContractValidation.responseOnly());
        Mockito.reset(noteGateway);

        for (JsonNode n : new JsonNode[] {n1, n2, n3, n4}) {
            JsonNode now = getNote(n.get("id").asString());
            assertThat(now.get("body").asString()).contains("[[alvo]]");
            assertThat(now.get("version").asLong()).isZero();
            assertThat(now.get("links").get(0).get("state").asString()).isEqualTo("RESOLVED");
        }
        JsonNode notMoved = getNote(alvo.get("id").asString());
        assertThat(notMoved.get("path").asString()).isEqualTo("alvo.md");
        assertThat(notMoved.get("version").asLong()).isZero();
        assertThat(moveNote(alvo.get("id").asString(), "novo.md", 0).get("rewrittenNotes").size()).isEqualTo(4);
    }

    @Test
    @DisplayName("CA4: versão velha é 409 VERSION_CONFLICT; caminho ocupado (inclusive só pela caixa) é 422 NOTE_PATH_TAKEN; caminho inválido é 422; mudar só a caixa é permitido")
    void deveValidarAMudanca() throws Exception {
        String a = createNote("a.md", "x").get("id").asString();
        createNote("b.md", "x");
        expectError(post("/v1/notes/" + a + "/move", "{\"newPath\":\"c.md\",\"version\":5}"), 409, "VERSION_CONFLICT");
        expectError(post("/v1/notes/" + a + "/move", "{\"newPath\":\"B.md\",\"version\":0}"), 422, "NOTE_PATH_TAKEN");
        expectError(post("/v1/notes/" + a + "/move", "{\"newPath\":\"../c.md\",\"version\":0}"), 422, "NOTE_PATH_INVALID");
        expectError(post("/v1/notes/" + a + "/move", "{\"newPath\":\"c.md\",\"version\":0,\"x\":1}"), 400, "VALIDATION_FAILED");
        JsonNode same = okBody(post("/v1/notes/" + a + "/move", "{\"newPath\":\"a.md\",\"version\":0}"));
        assertThat(same.get("rewrittenNotes").size()).isZero();
        assertThat(same.get("note").get("version").asLong()).isZero();
        assertThat(moveNote(a, "A.md", 0).get("note").get("path").asString()).isEqualTo("A.md");
    }

    @Test
    @DisplayName("CA5: link dentro de código não é reescrito; link da própria nota para ela mesma é; links pendentes que passam a casar com o nome novo resolvem sem reescrever")
    void deveRespeitarCodigoEResolverPendentes() throws Exception {
        JsonNode alvo = createNote("alvo.md", "eu [[alvo]] e `[[alvo]]`");
        JsonNode leitor = createNote("leitor.md", "[[alvo]] `[[alvo]]`\n```\n[[alvo]]\n```\n[[Futuro]]");
        JsonNode moved = moveNote(alvo.get("id").asString(), "novo.md", 0);
        assertThat(getNote(leitor.get("id").asString()).get("body").asString()).isEqualTo("[[novo]] `[[alvo]]`\n```\n[[alvo]]\n```\n[[Futuro]]");
        assertThat(moved.get("note").get("path").asString()).isEqualTo("novo.md");
        assertThat(getNote(alvo.get("id").asString()).get("body").asString()).isEqualTo("eu [[novo]] e `[[alvo]]`");
        JsonNode second = moveNote(alvo.get("id").asString(), "Futuro.md", 1);
        assertThat(second.get("rewrittenNotes").size()).isEqualTo(1);
        JsonNode pending = getNote(leitor.get("id").asString());
        assertThat(pending.get("body").asString()).endsWith("[[Futuro]]");
        for (JsonNode link : pending.get("links")) {
            if (link.get("text").asString().equals("Futuro")) {
                assertThat(link.get("state").asString()).isEqualTo("RESOLVED");
            }
        }
    }

    @Test
    @DisplayName("CA6: 500 notas com links para a nota renomeada: a operação termina em tempo medido e todas as 500 são reescritas, atomicamente")
    void deveMedirARenomeacaoCom500Notas() throws Exception {
        JsonNode alvo = createNote("Alvo.md", "alvo");
        String alvoId = alvo.get("id").asString();
        committed(() -> {
            jdbc.update("INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version) "
                + "SELECT gen_random_uuid(), ?, 'notas/n' || g || '.md', 'notas/n' || g || '.md', 'n' || g, 'Veja [[Alvo]] e [[Alvo|alias]] texto ' || g, ?, ?, 0 "
                + "FROM generate_series(1, 500) g", userId, NOW, NOW);
            jdbc.update("INSERT INTO note_links (id, user_id, note_id, position, origin, target_type, target_note_id, target_text, embed, context, created_at, updated_at) "
                + "SELECT gen_random_uuid(), user_id, id, 0, 'WIKILINK', 'NOTE', ?::uuid, 'Alvo', false, body, created_at, updated_at FROM notes WHERE user_id = ? AND id <> ?::uuid",
                alvoId, userId, alvoId);
        });
        long start = System.nanoTime();
        JsonNode moved = moveNote(alvoId, "Renomeado.md", 0);
        long millis = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[T110 CA6] renomear com 500 notas linkando: " + millis + " ms");
        assertThat(moved.get("rewrittenNotes").size()).isEqualTo(500);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notes WHERE user_id = ? AND body LIKE '%[[Renomeado]]%' AND body LIKE '%[[Renomeado|alias]]%'", Long.class, userId))
                .isEqualTo(500L);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notes WHERE user_id = ? AND body LIKE '%[[Alvo%'", Long.class, userId)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_links WHERE user_id = ? AND origin = 'WIKILINK' AND target_note_id = ?::uuid AND target_text = 'Renomeado'", Long.class,
                userId, alvoId)).isEqualTo(500L);
        assertThat(millis).isLessThan(60_000L);
    }
}
