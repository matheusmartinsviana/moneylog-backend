package com.moneylog.infra.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.sql.Timestamp;
import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("Notas: busca, filtros e ordenação (T111, S-163)")
class NoteSearchIT extends NotesItBase {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    @Test
    @DisplayName("CA1: q=revisão acha 'Revisao' e 'revisão' sem acento nem caixa; título antes de caminho antes de corpo; o trecho traz o termo (até 160 caracteres)")
    void deveBuscarSemAcentoEOrdenarPorRelevancia() throws Exception {
        createNote("so-no-corpo.md", "texto longo " + "x ".repeat(150) + "a revisão mensal fica aqui " + "y ".repeat(150));
        createNote("Revisao de outubro.md", "corpo sem o termo");
        createNote("revisão/geral.md", "corpo sem o termo tambem");
        createNote("outra.md", "nada a ver");
        createNote("REVISÃO final.md", "o título casa");
        JsonNode result = search("revisão");
        assertThat(paths(result)).containsExactlyInAnyOrder("so-no-corpo.md", "Revisao de outubro.md", "revisão/geral.md", "REVISÃO final.md");
        java.util.List<String> order = paths(result);
        assertThat(order.indexOf("so-no-corpo.md")).isEqualTo(3);
        assertThat(order.indexOf("revisão/geral.md")).isEqualTo(2);
        assertThat(order.subList(0, 2)).containsExactlyInAnyOrder("Revisao de outubro.md", "REVISÃO final.md");
        for (JsonNode item : result.get("items")) {
            assertThat(item.get("snippet").asString().length()).isLessThanOrEqualTo(160);
            assertThat(item.has("body")).isFalse();
        }
        JsonNode inBody = result.get("items").get(3);
        assertThat(inBody.get("snippet").asString()).containsIgnoringCase("revisão mensal");
        assertThat(paths(search("REVISAO"))).hasSize(4);
        assertThat(paths(search("revisao de outubro"))).containsExactly("Revisao de outubro.md");
    }

    @Test
    @DisplayName("CA2: % e _ do termo são escapados (sem curinga acidental); q acima de 200 caracteres ou com controle é 400")
    void deveEscaparCuringas() throws Exception {
        createNote("cem.md", "desconto de 100% hoje");
        createNote("sublinhado.md", "um_nome_assim");
        createNote("comum.md", "sem nada especial");
        assertThat(paths(search("%"))).containsExactly("cem.md");
        assertThat(paths(search("_"))).containsExactly("sublinhado.md");
        assertThat(paths(search("100%"))).containsExactly("cem.md");
        assertThat(paths(search("um_nome"))).containsExactly("sublinhado.md");
        assertThat(paths(search("u_nome"))).isEmpty();
        assertThat(paths(search("\\"))).isEmpty();
        assertThat(paths(search("%%%"))).isEmpty();
        mockMvc.perform(get("/v1/notes?q=" + "a".repeat(201))).andExpect(status().isBadRequest());
        assertThat(paths(search("a".repeat(200)))).isEmpty();
        mockMvc.perform(get("/v1/notes").queryParam("q", "a\u0000b")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/notes").queryParam("q", "a\u001bb")).andExpect(status().isBadRequest());
        assertThat(paths(body(mockMvc.perform(get("/v1/notes").queryParam("q", "  ")).andExpect(status().isOk()).andReturn()))).hasSize(3);
    }

    @Test
    @DisplayName("CA3/CA4/CA5: filtros por tag, pasta e vínculo; a lixeira só com status=TRASHED; ordenação por caminho, título e data; paginação com hasNext")
    void deveFiltrarEOrdenar() throws Exception {
        String a = createNote("reviews/2026-09.md", "#revisao-mensal um", "[" + link("GOAL", goalId) + "]").get("id").asString();
        createNote("reviews/2026-10.md", "#revisao-mensal dois", "[" + link("GOAL", goalId) + "," + link("TRANSACTION", notebookId) + "]");
        createNote("reviews/arquivo/2025.md", "#antigo tres");
        createNote("projetos/MG Tech.md", "quatro", "[" + monthLink("2026-10") + "]");
        String lixo = createNote("lixo.md", "#revisao-mensal cinco", "[" + link("GOAL", goalId) + "]").get("id").asString();
        trashNote(lixo);

        assertThat(paths(listNotes("tag=revisao-mensal&sort=PATH"))).containsExactly("reviews/2026-09.md", "reviews/2026-10.md");
        assertThat(paths(listNotes("folder=reviews&sort=PATH"))).containsExactly("reviews/2026-09.md", "reviews/2026-10.md", "reviews/arquivo/2025.md");
        assertThat(paths(listNotes("folder=/reviews/arquivo/"))).containsExactly("reviews/arquivo/2025.md");
        assertThat(paths(listNotes("folder=Reviews&sort=PATH"))).hasSize(3);
        assertThat(paths(listNotes("folder=revi"))).isEmpty();
        assertThat(paths(listNotes("linkedType=GOAL&linkedId=" + goalId + "&sort=PATH"))).containsExactly("reviews/2026-09.md", "reviews/2026-10.md");
        assertThat(paths(listNotes("linkedType=GOAL&linkedId=" + goalId + "&status=TRASHED"))).containsExactly("lixo.md");
        assertThat(paths(listNotes("linkedType=MONTHLY_SUMMARY&linkedKey=2026-10"))).containsExactly("projetos/MG Tech.md");
        assertThat(paths(listNotes("tag=revisao-mensal&linkedType=TRANSACTION&linkedId=" + notebookId))).containsExactly("reviews/2026-10.md");
        assertThat(paths(listNotes("sort=TITLE"))).containsExactly("reviews/arquivo/2025.md", "reviews/2026-09.md", "reviews/2026-10.md", "projetos/MG Tech.md").doesNotContain("lixo.md");
        assertThat(paths(listNotes(null))).doesNotContain("lixo.md").hasSize(4);
        assertThat(paths(listNotes("sort=UPDATED"))).hasSize(4);

        JsonNode page1 = listNotes("sort=PATH&pageSize=2");
        assertThat(page1.get("hasNext").asBoolean()).isTrue();
        assertThat(paths(page1)).containsExactly("projetos/MG Tech.md", "reviews/2026-09.md");
        JsonNode page2 = listNotes("sort=PATH&pageSize=2&page=2");
        assertThat(page2.get("hasNext").asBoolean()).isFalse();
        assertThat(paths(page2)).containsExactly("reviews/2026-10.md", "reviews/arquivo/2025.md");
        assertThat(paths(listNotes("sort=PATH&pageSize=2&page=3"))).isEmpty();
        assertThat(a).isNotBlank();
    }

    @Test
    @DisplayName("CA6: com 2.000 notas a busca responde em tempo medido (sem full-text no MVP)")
    void deveMedirABuscaCom2000Notas() throws Exception {
        committed(() -> jdbc.update("INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version) "
                + "SELECT gen_random_uuid(), ?, 'vol/n' || g || '.md', 'vol/n' || g || '.md', 'Nota ' || g, "
                + "repeat('Texto corrido de uma nota qualquer sobre financas, projetos e revisoes mensais. ', 30) || CASE WHEN g % 400 = 0 THEN ' dragao-azul' ELSE '' END, ?, ?, 0 "
                + "FROM generate_series(1, 2000) g", userId, NOW, NOW));
        long start = System.nanoTime();
        JsonNode hit = search("DRAGÃO-azul");
        long rare = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        JsonNode common = okBody(get("/v1/notes").queryParam("q", "revisoes").queryParam("pageSize", "50"));
        long frequent = (System.nanoTime() - start) / 1_000_000;
        start = System.nanoTime();
        JsonNode miss = search("inexistente-xyz");
        long none = (System.nanoTime() - start) / 1_000_000;
        System.out.println("[T111 CA6] 2.000 notas (~2,3 KB cada): termo raro " + rare + " ms, termo comum " + frequent + " ms, sem resultado " + none + " ms");
        assertThat(hit.get("items").size()).isEqualTo(5);
        assertThat(common.get("items").size()).isEqualTo(50);
        assertThat(common.get("hasNext").asBoolean()).isTrue();
        assertThat(miss.get("items").size()).isZero();
        assertThat(Math.max(rare, Math.max(frequent, none))).isLessThan(10_000L);
    }
}
