package com.moneylog.infra.note;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.note.domain.NotePath;
import com.moneylog.core.note.domain.WikilinkResolver;

@DisplayName("Notas: concorrência de criar, editar, mover e lixeira (ordem de travas, ADR-009)")
class NoteConcurrencyIT extends NotesItBase {

    private int[] call(CountDownLatch start, org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request) throws Exception {
        start.await();
        var response = mockMvc.perform(request).andReturn().getResponse();
        return new int[] {response.getStatus(), response.getContentAsString().contains("\"code\":\"CONFLICT\"") ? 1 : 0};
    }

    private Callable<int[]> post(CountDownLatch start, String url, String json) {
        return () -> call(start, MockMvcRequestBuilders.post(url).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private Callable<int[]> put(CountDownLatch start, String url, String json) {
        return () -> call(start, MockMvcRequestBuilders.put(url).header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON).content(json));
    }

    private List<int[]> runAll(List<Callable<int[]>> tasks, CountDownLatch start) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(tasks.size());
        try {
            List<Future<int[]>> futures = new ArrayList<>();
            for (Callable<int[]> task : tasks) {
                futures.add(pool.submit(task));
            }
            start.countDown();
            List<int[]> results = new ArrayList<>();
            for (Future<int[]> future : futures) {
                results.add(future.get(60, TimeUnit.SECONDS));
            }
            return results;
        } finally {
            pool.shutdownNow();
        }
    }

    private void assertWikilinksConsistent() {
        List<WikilinkResolver.Entry> index = jdbc.query("SELECT id, path FROM notes WHERE user_id = ? AND deleted_at IS NULL",
                (rs, row) -> new WikilinkResolver.Entry(rs.getObject("id", UUID.class), NotePath.of(rs.getString("path"))), userId);
        WikilinkResolver resolver = new WikilinkResolver(index);
        Map<String, UUID> stored = new HashMap<>();
        jdbc.query("SELECT id, target_text, target_note_id FROM note_links WHERE user_id = ? AND origin = 'WIKILINK'", (org.springframework.jdbc.core.RowCallbackHandler) rs -> {
            UUID target = rs.getObject("target_note_id", UUID.class);
            UUID expected = resolver.resolve(rs.getString("target_text")).orElse(null);
            assertThat(target).as("link %s '%s'", rs.getString("id"), rs.getString("target_text")).isEqualTo(expected);
            stored.put(rs.getString("id"), target);
        }, userId);
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_links l JOIN notes n ON n.id = l.target_note_id WHERE l.user_id = ? AND n.deleted_at IS NOT NULL AND l.origin = 'WIKILINK'",
                Long.class, userId)).isZero();
    }

    @Test
    @DisplayName("8 criações simultâneas do mesmo caminho: exatamente uma é 201 e as outras 422 NOTE_PATH_TAKEN, nunca 5xx")
    void deveCriarOMesmoCaminhoUmaVezSo() throws Exception {
        CountDownLatch start = new CountDownLatch(1);
        List<Callable<int[]>> tasks = new ArrayList<>();
        for (int i = 0; i < 8; i++) {
            tasks.add(post(start, "/v1/notes", noteJson("mesmo.md", "corpo " + i)));
        }
        List<int[]> results = runAll(tasks, start);
        assertThat(results.stream().filter(r -> r[0] == 201).count()).isEqualTo(1L);
        assertThat(results.stream().filter(r -> r[0] == 422).count()).isEqualTo(7L);
        assertThat(results).noneMatch(r -> r[0] >= 500);
        assertThat(count("notes")).isEqualTo(1L);
    }

    @Test
    @DisplayName("criar a nota B enquanto se edita A (que cita [[B]]): o link de A nunca fica pendente para sempre")
    void deveResolverOLinkDeQuemEditaEnquantoOAlvoNasce() throws Exception {
        for (int round = 0; round < 6; round++) {
            String a = createNote("a" + round + ".md", "sem link ainda").get("id").asString();
            CountDownLatch start = new CountDownLatch(1);
            List<int[]> results = runAll(List.of(put(start, "/v1/notes/" + a, "{\"body\":\"agora cito [[alvo" + round + "]]\",\"version\":0}"),
                    post(start, "/v1/notes", noteJson("alvo" + round + ".md", "o alvo"))), start);
            assertThat(results).allMatch(r -> r[0] == 200 || r[0] == 201);
            JsonNode note = getNote(a);
            assertThat(note.get("links").get(0).get("state").asString()).as("rodada %d", round).isEqualTo("RESOLVED");
        }
        assertWikilinksConsistent();
    }

    @Test
    @DisplayName("editar × mover × lixeira × restaurar a mesma nota e criar notas ao mesmo tempo: só 200/201/404/409/422, sem 500 e sem links órfãos")
    void deveSuportarMisturaDeOperacoes() throws Exception {
        String alvo = createNote("alvo.md", "o alvo").get("id").asString();
        List<String> leitores = new ArrayList<>();
        for (int i = 0; i < 6; i++) {
            leitores.add(createNote("leitor" + i + ".md", "veja [[alvo]] e [[novo]] e [[renomeado]] " + i).get("id").asString());
        }
        for (int round = 0; round < 5; round++) {
            CountDownLatch start = new CountDownLatch(1);
            long version = getNote(alvo).get("version").asLong();
            List<Callable<int[]>> tasks = new ArrayList<>();
            tasks.add(put(start, "/v1/notes/" + alvo, "{\"body\":\"editado " + round + "\",\"version\":" + version + "}"));
            tasks.add(post(start, "/v1/notes/" + alvo + "/move", "{\"newPath\":\"renomeado" + (round % 2 == 0 ? "" : "2") + ".md\",\"version\":" + version + "}"));
            tasks.add(post(start, "/v1/notes/" + alvo + "/trash", ""));
            tasks.add(post(start, "/v1/notes/" + alvo + "/restore", ""));
            tasks.add(post(start, "/v1/notes", noteJson("novo" + round + ".md", "nova " + round)));
            tasks.add(post(start, "/v1/notes", noteJson("novo.md", "nova " + round)));
            tasks.add(put(start, "/v1/notes/" + leitores.get(round) , "{\"body\":\"[[alvo]] [[novo]] editado\",\"version\":0}"));
            tasks.add(post(start, "/v1/notes/" + leitores.get(round + 1) + "/trash", ""));
            List<int[]> results = runAll(tasks, start);
            for (int[] r : results) {
                assertThat(r[0]).as("status da rodada %d", round).isIn(200, 201, 404, 409, 422);
            }
            assertWikilinksConsistent();
            if (getNote(alvo).get("trashedAt").isNull() == false) {
                restoreNote(alvo);
            }
            for (String leitor : leitores) {
                if (!getNote(leitor).get("trashedAt").isNull()) {
                    restoreNote(leitor);
                }
            }
            assertWikilinksConsistent();
        }
        assertThat(jdbc.queryForObject("SELECT count(*) FROM (SELECT 1 FROM notes WHERE user_id = ? AND deleted_at IS NULL GROUP BY path_key HAVING count(*) > 1) d", Long.class, userId)).isZero();
    }
}
