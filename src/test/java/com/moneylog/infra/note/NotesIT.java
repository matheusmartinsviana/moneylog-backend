package com.moneylog.infra.note;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.moneylog.util.ContractValidation;

/**
 * T108 e T109 (Etapa 6): notas, lixeira, vínculos e backlinks pela API real, com o contrato validando toda requisição e resposta. Cenário do
 * {@link NotesItBase}: compra do notebook, meta, dívida, pessoa, cartão, fatura e conta.
 */
@DisplayName("Notas: API, persistência, lixeira, vínculos e backlinks (T108, T109)")
class NotesIT extends NotesItBase {

    private static final Timestamp NOW = Timestamp.from(Instant.parse("2026-10-10T12:00:00Z"));

    // ---------------------------------------------------------------- T108

    @Test
    @DisplayName("T108 CA1: POST /v1/notes devolve 201 + Location, versão 0, título = nome do arquivo, tags e links do corpo (resolvidos e pendentes) com origem WIKILINK")
    void deveCriarENotaComDerivados() throws Exception {
        JsonNode macbook = createNote("MacBook.md", "O notebook");
        MvcResult created = mockMvc.perform(post("/v1/notes", noteJson("reviews/2026-10.md", "# Outubro\nVeja [[MacBook]] e [[Inexistente|alias]] #revisao-mensal")))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.version").value(0))
                .andExpect(jsonPath("$.title").value("2026-10")).andExpect(jsonPath("$.path").value("reviews/2026-10.md"))
                .andExpect(jsonPath("$.tags[0]").value("revisao-mensal")).andExpect(jsonPath("$.trashedAt").doesNotExist())
                .andExpect(jsonPath("$.links.length()").value(2)).andReturn();
        JsonNode note = body(created);
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/notes/" + note.get("id").asString());
        JsonNode resolved = note.get("links").get(0);
        assertThat(resolved.get("origin").asString()).isEqualTo("WIKILINK");
        assertThat(resolved.get("state").asString()).isEqualTo("RESOLVED");
        assertThat(resolved.get("target").get("id").asString()).isEqualTo(macbook.get("id").asString());
        assertThat(resolved.get("target").get("label").asString()).isEqualTo("MacBook");
        JsonNode pending = note.get("links").get(1);
        assertThat(pending.get("state").asString()).isEqualTo("PENDING");
        assertThat(pending.get("text").asString()).isEqualTo("Inexistente");
        assertThat(pending.get("alias").asString()).isEqualTo("alias");
        assertThat(pending.get("target").isNull()).isTrue();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_links WHERE user_id = ? AND origin = 'WIKILINK'", Long.class, userId)).isEqualTo(2L);
        assertThat(getNote(note.get("id").asString()).get("body").asString()).isEqualTo("# Outubro\nVeja [[MacBook]] e [[Inexistente|alias]] #revisao-mensal");
    }

    @Test
    @DisplayName("T108 CA2: caminho usado por nota ativa (inclusive em outra caixa) é 422 NOTE_PATH_TAKEN; depois da lixeira o mesmo caminho pode ser reutilizado")
    void deveRecusarCaminhoOcupadoELiberarPelaLixeira() throws Exception {
        String first = createNote("Reviews/2026-10.md", "a").get("id").asString();
        expectError(post("/v1/notes", noteJson("reviews/2026-10.md", "b")), 422, "NOTE_PATH_TAKEN");
        trashNote(first);
        JsonNode again = createNote("reviews/2026-10.md", "b");
        assertThat(again.get("path").asString()).isEqualTo("reviews/2026-10.md");
        assertThat(count("notes")).isEqualTo(2L);
        // o índice parcial é a segunda barreira (a aplicação checa antes): duas ativas com a mesma chave são recusadas pelo banco
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version) "
                + "VALUES (?, ?, 'x.md', 'reviews/2026-10.md', 'x', '', ?, ?, 0)", UUID.randomUUID(), userId, NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("T108 CA3: PUT com a versão certa devolve 200 e sobe a versão; versão velha é 409 VERSION_CONFLICT; texto recalcula tags e links")
    void deveEditarComLockOtimista() throws Exception {
        JsonNode note = createNote("a.md", "[[b]] #um");
        String id = note.get("id").asString();
        JsonNode edited = editNote(id, "[[c]] #dois", 0);
        assertThat(edited.get("version").asLong()).isEqualTo(1);
        assertThat(edited.get("tags").get(0).asString()).isEqualTo("dois");
        assertThat(edited.get("links").get(0).get("text").asString()).isEqualTo("c");
        expectError(put("/v1/notes/" + id, "{\"body\":\"x\",\"version\":0}"), 409, "VERSION_CONFLICT");
        assertThat(getNote(id).get("body").asString()).isEqualTo("[[c]] #dois");
    }

    @Test
    @DisplayName("T108 CA3: com 5.000 notas (a lixeira conta), criar outra é 422 NOTE_LIMIT_REACHED")
    void deveLimitarEm5000Notas() throws Exception {
        committed(() -> jdbc.update("INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version) "
                + "SELECT gen_random_uuid(), ?, 'n' || g || '.md', 'n' || g || '.md', 'n' || g, '', ?, ?, 0 FROM generate_series(1, 5000) g", userId, NOW, NOW));
        expectError(post("/v1/notes", noteJson("nova.md", "x")), 422, "NOTE_LIMIT_REACHED");
        assertThat(count("notes")).isEqualTo(5000L);
    }

    @Test
    @DisplayName("T108 CA4/CA5: trash tira da lista, da busca e dos backlinks e deixa pendentes os links para a nota; restore devolve; restore com caminho ocupado é NOTE_PATH_TAKEN; esvaziar remove só a lixeira")
    void deveTratarALixeira() throws Exception {
        String macbook = createNote("MacBook.md", "notebook").get("id").asString();
        String review = createNote("review.md", "[[MacBook]] revisão única").get("id").asString();
        assertThat(getNote(review).get("links").get(0).get("state").asString()).isEqualTo("RESOLVED");
        assertThat(backlinks(macbook).get("items").size()).isEqualTo(1);

        JsonNode trashed = trashNote(macbook);
        assertThat(trashed.get("trashedAt").isNull()).isFalse();
        assertThat(paths(listNotes(null))).containsExactly("review.md");
        assertThat(paths(listNotes("status=TRASHED"))).containsExactly("MacBook.md");
        assertThat(paths(listNotes("q=notebook"))).isEmpty();
        assertThat(getNote(review).get("links").get(0).get("state").asString()).isEqualTo("PENDING");
        expectError(postNoBody("/v1/notes/" + macbook + "/trash"), 422, "NOTE_ALREADY_TRASHED");
        expectError(put("/v1/notes/" + macbook, "{\"body\":\"x\",\"version\":1}"), 422, "NOTE_TRASHED");
        expectError(post("/v1/notes/" + macbook + "/move", "{\"newPath\":\"z.md\",\"version\":1}"), 422, "NOTE_TRASHED");
        expectError(put("/v1/notes/" + macbook + "/links", "{\"links\":[],\"version\":1}"), 422, "NOTE_TRASHED");
        assertThat(getNote(macbook).get("trashedAt").isNull()).isFalse();

        String taken = createNote("MacBook.md", "outra com o mesmo caminho").get("id").asString();
        expectError(postNoBody("/v1/notes/" + macbook + "/restore"), 422, "NOTE_PATH_TAKEN");
        trashNote(taken);
        JsonNode restored = restoreNote(macbook);
        assertThat(restored.get("trashedAt").isNull()).isTrue();
        assertThat(getNote(review).get("links").get(0).get("state").asString()).isEqualTo("RESOLVED");
        expectError(postNoBody("/v1/notes/" + macbook + "/restore"), 422, "NOTE_NOT_TRASHED");

        // esvaziar: só as notas já na lixeira do usuário; nenhuma entidade financeira é tocada
        String bearerOther = otherUserBearer();
        mockMvc.perform(postAs(bearerOther, "/v1/notes", noteJson("alheia.md", "x"))).andExpect(status().isCreated());
        mockMvc.perform(MockMvcRequestBuildersDelete.as(bearerOther)).andExpect(status().isNoContent());
        long transactions = count("transactions");
        long goals = count("goals");
        mockMvc.perform(delete("/v1/notes/trash")).andExpect(status().isNoContent());
        assertThat(count("notes")).isEqualTo(2L);
        assertThat(paths(listNotes("status=TRASHED"))).isEmpty();
        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("goals")).isEqualTo(goals);
        mockMvc.perform(delete("/v1/notes/trash")).andExpect(status().isNoContent());
        assertThat(jdbc.queryForObject("SELECT count(*) FROM notes WHERE title = 'alheia'", Long.class)).isEqualTo(1L);
    }

    /** DELETE /v1/notes/trash com o token de outro usuário. */
    private static final class MockMvcRequestBuildersDelete {
        static org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder as(String bearer) {
            return org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/notes/trash").header("Authorization", bearer);
        }
    }

    @Test
    @DisplayName("T108 CA6: nota de outro usuário é 404 em todas as rotas e não aparece na lista, nos backlinks nem nos vínculos")
    void deveIsolarOsUsuarios() throws Exception {
        String id = createNote("a.md", "x").get("id").asString();
        String other = otherUserBearer();
        for (var request : java.util.List.of(getAs(other, "/v1/notes/" + id), getAs(other, "/v1/notes/" + id + "/backlinks"),
                putAs(other, "/v1/notes/" + id, "{\"body\":\"y\",\"version\":0}"), putAs(other, "/v1/notes/" + id + "/links", "{\"links\":[],\"version\":0}"),
                postAs(other, "/v1/notes/" + id + "/move", "{\"newPath\":\"b.md\",\"version\":0}"), postNoBodyAs(other, "/v1/notes/" + id + "/trash"),
                postNoBodyAs(other, "/v1/notes/" + id + "/restore"))) {
            mockMvc.perform(request).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("NOTE_NOT_FOUND")).andExpect(ContractValidation.responseOnly());
        }
        mockMvc.perform(getAs(other, "/v1/notes")).andExpect(status().isOk()).andExpect(jsonPath("$.items.length()").value(0));
        mockMvc.perform(getAs(other, "/v1/notes/tags")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        expectError(get("/v1/notes/" + UUID.randomUUID()), 404, "NOTE_NOT_FOUND");
        assertThat(getNote(id).get("body").asString()).isEqualTo("x");
    }

    @Test
    @DisplayName("entrada: campo desconhecido, corpo ausente, controle no texto e JSON inválido são 400; caminho inválido e nota grande são 422")
    void deveValidarAEntrada() throws Exception {
        expectError(post("/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\",\"extra\":1}"), 400, "VALIDATION_FAILED").andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
        expectError(post("/v1/notes", "{\"path\":\"a.md\"}"), 400, "VALIDATION_FAILED");
        expectError(post("/v1/notes", "{\"body\":\"x\"}"), 400, "VALIDATION_FAILED");
        expectError(post("/v1/notes", "{\"path\":\"a.md\",\"body\":\"a\\u0000b\"}"), 400, "VALIDATION_FAILED");
        expectError(post("/v1/notes", "{\"path\":\"a.md\",\"body\":\"a\\u001bb\"}"), 400, "VALIDATION_FAILED");
        expectError(post("/v1/notes", "{\"path\":\"a\\u0000.md\",\"body\":\"x\"}"), 400, "VALIDATION_FAILED");
        expectError(post("/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\""), 400, "VALIDATION_FAILED");
        expectError(post("/v1/notes", "{\"path\":\"a.md\",\"body\":\"x\",\"links\":[{\"type\":\"PLANET\",\"id\":\"" + UUID.randomUUID() + "\"}]}"), 400, "VALIDATION_FAILED");
        expectError(put("/v1/notes/" + createNote("b.md", "x").get("id").asString(), "{\"body\":\"x\"}"), 400, "VALIDATION_FAILED");
        for (String bad : new String[] {"../x.md", "/x.md", "a\\\\b.md", "sem-extensao", "a/../b.md", ".oculta/x.md", "con.md", "a?b.md", "a/b/c/d/e/f/g/h/i/j/k.md", "x".repeat(300) + ".md"}) {
            expectError(post("/v1/notes", noteJson(bad.replace("\\\\", "\\"), "x")), 422, "NOTE_PATH_INVALID");
        }
        expectError(post("/v1/notes", noteJson("a.md", "a".repeat(204_800 + 1))), 422, "NOTE_TOO_LARGE");
        expectError(post("/v1/notes", noteJson("a.md", "é".repeat(102_401))), 422, "NOTE_TOO_LARGE");
        assertThat(count("notes")).isEqualTo(1L);
        mockMvc.perform(post("/v1/notes", noteJson("exata.md", "a".repeat(204_800)))).andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/notes", noteJson("tab.md", "linha\r\n\tcom tab e quebra"))).andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        // S-273: YAML que não é um mapa seguro não é frontmatter: o texto é corpo e volta como foi escrito
        mockMvc.perform(post("/v1/notes", noteJson("fm.md", "---\nx: !!java.lang.Object {}\n---\n"))).andExpect(status().isCreated())
                .andExpect(jsonPath("$.body").value("---\nx: !!java.lang.Object {}\n---\n")).andExpect(ContractValidation.conforms());
        expectError(post("/v1/notes", noteJson("fm3.md", "---\ntitle: \"a\\x00b\"\n---\n")), 422, "TEXT_INVALID_CHARACTERS");
        expectError(post("/v1/notes", noteJson("fm2.md", "---\nmoneylog:\n  links: []\n---\n")), 422, "NOTE_FRONTMATTER_RESERVED");
        mockMvc.perform(get("/v1/notes?page=0")).andExpect(status().isBadRequest());
        mockMvc.perform(get("/v1/notes?pageSize=51")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("idempotência: a mesma Idempotency-Key e o mesmo corpo devolvem a mesma nota; outro corpo é 409 IDEMPOTENCY_KEY_REUSED")
    void deveSerIdempotente() throws Exception {
        String key = UUID.randomUUID().toString();
        JsonNode first = body(mockMvc.perform(post("/v1/notes", noteJson("a.md", "x")).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode replay = body(mockMvc.perform(post("/v1/notes", noteJson("a.md", "x")).header("Idempotency-Key", key)).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms()).andReturn());
        assertThat(replay.get("id").asString()).isEqualTo(first.get("id").asString());
        assertThat(count("notes")).isEqualTo(1L);
        expectError(post("/v1/notes", noteJson("a.md", "outro")).header("Idempotency-Key", key), 409, "IDEMPOTENCY_KEY_REUSED");
    }

    @Test
    @DisplayName("a lista nunca traz o corpo (nem nos itens, nem no schema) e o texto sai como entrou: HTML/JS é dado, escapado no JSON, sem sniffing")
    void deveDevolverOTextoComoEntrouESemCorpoNaLista() throws Exception {
        String xss = "<script>alert(1)</script><img src=x onerror=alert(1)> [x](javascript:alert(1)) <svg onload=alert(1)> &lt;b&gt;";
        String id = createNote("xss.md", xss).get("id").asString();
        MvcResult result = mockMvc.perform(get("/v1/notes/" + id)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Content-Type", org.hamcrest.Matchers.startsWith("application/json")))
                .andExpect(header().string("X-Content-Type-Options", "nosniff")).andReturn();
        assertThat(body(result).get("body").asString()).isEqualTo(xss);
        assertThat(result.getResponse().getContentType()).startsWith("application/json");
        JsonNode list = search("alert");
        assertThat(list.get("items").get(0).has("body")).isFalse();
        assertThat(list.get("items").get(0).get("snippet").asString()).contains("<script>alert(1)</script>").hasSizeLessThanOrEqualTo(160);
        assertThat(listNotes(null).get("items").get(0).get("snippet").isNull()).isTrue();
    }

    @Test
    @DisplayName("garantia 1: criar, editar, mover, vincular, mandar para a lixeira, restaurar e esvaziar não mudam saldo, razão, consumo nem dashboard")
    void naoDeveAlterarNenhumaLenteFinanceira() throws Exception {
        long postings = count("ledger_postings");
        long transactions = count("transactions");
        String balance = accountBalance();
        JsonNode summaryBefore = summary();
        JsonNode goalBefore = okBody(get("/v1/goals/" + goalId));
        JsonNode debtBefore = debt(debtId);

        JsonNode note = createNote("reviews/2026-10.md", "Revisão [[MacBook]]", "[" + link("TRANSACTION", notebookId) + "," + link("GOAL", goalId) + "," + link("DEBT", debtId)
                + "," + link("ACCOUNT", nubank) + "," + link("CARD", card) + "," + link("PERSON", maria) + "," + monthLink("2026-10") + "]");
        String id = note.get("id").asString();
        editNote(id, "Revisão editada [[MacBook]]", 0);
        moveNote(id, "reviews/outubro.md", 1);
        setLinks(id, "[" + link("GOAL", goalId) + "]", 2);
        trashNote(id);
        restoreNote(id);
        trashNote(id);
        mockMvc.perform(delete("/v1/notes/trash")).andExpect(status().isNoContent());

        assertThat(count("ledger_postings")).isEqualTo(postings);
        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(accountBalance()).isEqualTo(balance);
        assertThat(summary()).isEqualTo(summaryBefore);
        assertThat(okBody(get("/v1/goals/" + goalId))).isEqualTo(goalBefore);
        assertThat(debt(debtId)).isEqualTo(debtBefore);
        assertThat(ledgerTotal()).isZero();
    }

    // ---------------------------------------------------------------- T109

    @Test
    @DisplayName("T109 CA1/CA2: vínculo à compra do notebook e ao resumo de outubro; GET /v1/links traz a nota; alvo de outro usuário ou inexistente é 422 NOTE_LINK_TARGET_NOT_FOUND; 2026-13 é 422")
    void deveVincularEntidadesELerOsVinculos() throws Exception {
        JsonNode note = createNote("reviews/2026-10.md", "x", "[" + link("TRANSACTION", notebookId) + "," + monthLink("2026-10") + "]");
        JsonNode tx = linkOf(note, "TRANSACTION");
        assertThat(tx.get("origin").asString()).isEqualTo("EXPLICIT");
        assertThat(tx.get("state").asString()).isEqualTo("RESOLVED");
        assertThat(tx.get("target").get("id").asString()).isEqualTo(notebookId);
        assertThat(tx.get("target").get("label").asString()).isEqualTo("Notebook");
        assertThat(tx.get("target").get("status").asString()).isEqualTo("ACTIVE");
        assertThat(linkOf(note, "MONTHLY_SUMMARY").get("target").get("key").asString()).isEqualTo("2026-10");

        assertThat(paths(okBody(get("/v1/links?targetType=TRANSACTION&targetId=" + notebookId)))).containsExactly("reviews/2026-10.md");
        assertThat(paths(okBody(get("/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-10")))).containsExactly("reviews/2026-10.md");
        assertThat(paths(okBody(get("/v1/links?targetType=MONTHLY_SUMMARY&targetKey=2026-09")))).isEmpty();
        assertThat(paths(listNotes("linkedType=TRANSACTION&linkedId=" + notebookId))).containsExactly("reviews/2026-10.md");

        String id = note.get("id").asString();
        String foreign = UUID.randomUUID().toString();
        expectError(put("/v1/notes/" + id + "/links", "{\"links\":[" + link("TRANSACTION", foreign) + "],\"version\":0}"), 422, "NOTE_LINK_TARGET_NOT_FOUND");
        expectError(put("/v1/notes/" + id + "/links", "{\"links\":[" + monthLink("2026-13") + "],\"version\":0}"), 422, "NOTE_LINK_INVALID");
        expectError(put("/v1/notes/" + id + "/links", "{\"links\":[{\"type\":\"GOAL\"}],\"version\":0}"), 422, "NOTE_LINK_INVALID");
        expectError(post("/v1/notes", noteJson("b.md", "x", "[" + link("GOAL", foreign) + "]")), 422, "NOTE_LINK_TARGET_NOT_FOUND");
        // o alvo é de OUTRO usuário
        String other = otherUserBearer();
        String othersGoal = body(mockMvc.perform(postAs(other, "/v1/goals", "{\"name\":\"Alheia\",\"target\":\"10.00\",\"priority\":\"LOW\"}")).andExpect(status().isCreated()).andReturn())
                .get("id").asString();
        expectError(put("/v1/notes/" + id + "/links", "{\"links\":[" + link("GOAL", othersGoal) + "],\"version\":0}"), 422, "NOTE_LINK_TARGET_NOT_FOUND");
        assertThat(getNote(id).get("version").asLong()).isZero();
        expectError(get("/v1/links?targetType=GOAL"), 400, "VALIDATION_FAILED");
        expectError(get("/v1/links?targetType=MONTHLY_SUMMARY&targetId=" + goalId), 400, "VALIDATION_FAILED");
        expectError(get("/v1/links"), 400, "VALIDATION_FAILED");
        assertThat(paths(okBody(get("/v1/links?targetType=GOAL&targetId=" + othersGoal)))).isEmpty();
    }

    @Test
    @DisplayName("T109 CA1: todos os tipos de alvo vinculam: transação, conta, cartão, fatura, pessoa, dívida, meta, resumo mensal e nota")
    void deveVincularTodosOsTipos() throws Exception {
        String other = createNote("alvo.md", "x").get("id").asString();
        JsonNode note = createNote("tudo.md", "x", "[" + link("TRANSACTION", notebookId) + "," + link("ACCOUNT", nubank) + "," + link("CARD", card) + "," + link("INVOICE", invoiceId)
                + "," + link("PERSON", maria) + "," + link("DEBT", debtId) + "," + link("GOAL", goalId) + "," + monthLink("2026-10") + "," + link("NOTE", other) + "]");
        assertThat(note.get("links").size()).isEqualTo(9);
        assertThat(linkOf(note, "ACCOUNT").get("target").get("label").asString()).isEqualTo("Nubank");
        assertThat(linkOf(note, "CARD").get("target").get("label").asString()).isEqualTo("Roxinho");
        assertThat(linkOf(note, "INVOICE").get("target").get("label").asString()).startsWith("Roxinho");
        assertThat(linkOf(note, "INVOICE").get("target").get("status").isNull()).isTrue();
        assertThat(linkOf(note, "PERSON").get("target").get("label").asString()).isEqualTo("Maria");
        assertThat(linkOf(note, "DEBT").get("target").get("label").asString()).isEqualTo("Almoço pago por Maria");
        assertThat(linkOf(note, "GOAL").get("target").get("label").asString()).isEqualTo("Reserva de emergência");
        assertThat(linkOf(note, "NOTE").get("target").get("label").asString()).isEqualTo("alvo");
        for (String[] pair : new String[][] {{"ACCOUNT", nubank}, {"CARD", card}, {"INVOICE", invoiceId}, {"PERSON", maria}, {"DEBT", debtId}, {"GOAL", goalId}, {"NOTE", other}}) {
            assertThat(paths(okBody(get("/v1/links?targetType=" + pair[0] + "&targetId=" + pair[1])))).as(pair[0]).contains("tudo.md");
        }
        // as colunas tipadas têm FK composta: só uma referência por vínculo
        assertThat(jdbc.queryForObject("SELECT count(*) FROM note_links WHERE user_id = ? AND origin = 'EXPLICIT' AND num_nonnulls(target_note_id, target_transaction_id, target_account_id, "
                + "target_card_id, target_invoice_id, target_person_id, target_debt_id, target_goal_id, target_key, target_text) = 1", Long.class, userId)).isEqualTo(9L);
    }

    @Test
    @DisplayName("T109 CA3: transação estornada: o vínculo continua, target.status = VOIDED, a nota não some e GET /v1/links ainda a traz (S-164)")
    void deveManterOVinculoDeTransacaoEstornada() throws Exception {
        String id = createNote("reviews/2026-10.md", "x", "[" + link("TRANSACTION", notebookId) + "]").get("id").asString();
        mockMvc.perform(post("/v1/transactions/" + notebookId + "/void", voidBody("compra errada"))).andExpect(status().isOk());
        JsonNode note = getNote(id);
        assertThat(linkOf(note, "TRANSACTION").get("state").asString()).isEqualTo("RESOLVED");
        assertThat(linkOf(note, "TRANSACTION").get("target").get("status").asString()).isEqualTo("VOIDED");
        assertThat(paths(okBody(get("/v1/links?targetType=TRANSACTION&targetId=" + notebookId)))).containsExactly("reviews/2026-10.md");
        // meta arquivada também continua alvo válido
        mockMvc.perform(post("/v1/goals/" + goalId + "/archive", "")).andExpect(status().isOk());
        JsonNode linked = setLinks(id, "[" + link("GOAL", goalId) + "]", 0);
        assertThat(linkOf(linked, "GOAL").get("target").get("status").asString()).isEqualTo("ARCHIVED");
    }

    @Test
    @DisplayName("T109 CA4: [[MacBook]] em duas notas: os backlinks da nota MacBook listam as duas com o trecho de contexto (até 160 caracteres); nota na lixeira não aparece; nota com wikilink e vínculo explícito aparece uma vez")
    void deveListarBacklinks() throws Exception {
        String macbook = createNote("MacBook.md", "notebook").get("id").asString();
        String longText = "a ".repeat(200) + "veja [[MacBook]] aqui " + "b ".repeat(200);
        String review = createNote("reviews/2026-10.md", longText).get("id").asString();
        String project = createNote("projetos/MG Tech.md", "Comprei o [[macbook|Mac]] para o cliente").get("id").asString();
        String both = createNote("ambos.md", "[[MacBook]]", "[" + link("NOTE", macbook) + "]").get("id").asString();
        JsonNode items = backlinks(macbook).get("items");
        assertThat(items.size()).isEqualTo(3);
        java.util.Map<String, String> byPath = new java.util.HashMap<>();
        items.forEach(i -> byPath.put(i.get("path").asString(), i.get("context").asString()));
        assertThat(byPath).containsOnlyKeys("ambos.md", "projetos/MG Tech.md", "reviews/2026-10.md");
        assertThat(byPath.get("reviews/2026-10.md")).contains("[[MacBook]]").hasSizeLessThanOrEqualTo(160);
        assertThat(byPath.get("projetos/MG Tech.md")).isEqualTo("Comprei o [[macbook|Mac]] para o cliente");
        assertThat(items.get(0).get("title").asString()).isNotBlank();
        trashNote(project);
        assertThat(backlinks(macbook).get("items").size()).isEqualTo(2);
        assertThat(backlinks(review).get("items").size()).isZero();
        assertThat(paths(okBody(get("/v1/links?targetType=NOTE&targetId=" + macbook)))).containsExactlyInAnyOrder("ambos.md", "reviews/2026-10.md");
        assertThat(both).isNotBlank();
    }

    @Test
    @DisplayName("T109 CA5: editar o corpo recalcula só os WIKILINK; os vínculos explícitos permanecem; PUT /links troca só os explícitos")
    void deveRecalcularSoOsWikilinks() throws Exception {
        JsonNode note = createNote("a.md", "[[x]]", "[" + link("GOAL", goalId) + "]");
        String id = note.get("id").asString();
        JsonNode edited = editNote(id, "[[y]] e [[z]]", 0);
        assertThat(edited.get("links").size()).isEqualTo(3);
        assertThat(linkOf(edited, "GOAL").get("origin").asString()).isEqualTo("EXPLICIT");
        JsonNode relinked = setLinks(id, "[" + link("PERSON", maria) + "]", 1);
        assertThat(relinked.get("links").size()).isEqualTo(3);
        assertThat(java.util.stream.StreamSupport.stream(relinked.get("links").spliterator(), false).filter(l -> l.get("origin").asString().equals("WIKILINK")).count()).isEqualTo(2L);
        assertThat(setLinks(id, "[]", 2).get("links").size()).isEqualTo(2);
        expectError(put("/v1/notes/" + id + "/links", "{\"links\":[],\"version\":0}"), 409, "VERSION_CONFLICT");
    }

    @Test
    @DisplayName("T109 CA6: vínculo explícito NOTE e wikilink para a mesma nota não duplicam o backlink; a nota não se vincula a ela mesma")
    void naoDeveDuplicarNemVincularASiMesma() throws Exception {
        String a = createNote("a.md", "x").get("id").asString();
        expectError(put("/v1/notes/" + a + "/links", "{\"links\":[" + link("NOTE", a) + "],\"version\":0}"), 422, "NOTE_LINK_INVALID");
        String b = createNote("b.md", "[[a]]", "[" + link("NOTE", a) + "]").get("id").asString();
        assertThat(backlinks(a).get("items").size()).isEqualTo(1);
        assertThat(b).isNotBlank();
    }

    // ---------------------------------------------------------------- banco: a segunda barreira

    @Test
    @DisplayName("esquema: FK composta recusa vínculo para entidade de OUTRO usuário; CHECK exige exatamente uma referência e a coluna do tipo; wikilink não aponta para entidade; texto > 200 KB é recusado")
    void deveProtegerOEsquema() throws Exception {
        String id = createNote("a.md", "x").get("id").asString();
        UUID otherUser = UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, otherUser, "fk-" + otherUser + "@moneylog.com"));
        UUID foreignGoal = UUID.randomUUID();
        committed(() -> jdbc.update("INSERT INTO goals (id, user_id, name, target_cents, currency, priority, archived, created_at, updated_at, version) "
                + "VALUES (?, ?, 'x', 100, 'BRL', 'LOW', false, ?, ?, 0)", foreignGoal, otherUser, NOW, NOW));
        String insert = "INSERT INTO note_links (id, user_id, note_id, position, origin, target_type, %s created_at, updated_at) VALUES (?, ?, ?, 0, ?, ?, %s ?, ?)";
        assertThatThrownBy(() -> jdbc.update(String.format(insert, "target_goal_id,", "?,"), UUID.randomUUID(), userId, UUID.fromString(id), "EXPLICIT", "GOAL", foreignGoal, NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_note_links_target_goal_id_user");
        assertThatThrownBy(() -> jdbc.update(String.format(insert, "", ""), UUID.randomUUID(), userId, UUID.fromString(id), "EXPLICIT", "GOAL", NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_note_links_explicit_single_reference");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO note_links (id, user_id, note_id, position, origin, target_type, target_goal_id, target_key, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, 'EXPLICIT', 'GOAL', ?, '2026-10', ?, ?)", UUID.randomUUID(), userId, UUID.fromString(id), UUID.fromString(goalId), NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update(String.format(insert, "target_goal_id,", "?,"), UUID.randomUUID(), userId, UUID.fromString(id), "EXPLICIT", "PERSON", UUID.fromString(goalId), NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_note_links_column_matches_type");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO note_links (id, user_id, note_id, position, origin, target_type, target_text, target_goal_id, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, 'WIKILINK', 'GOAL', 'x', ?, ?, ?)", UUID.randomUUID(), userId, UUID.fromString(id), UUID.fromString(goalId), NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class);
        assertThatThrownBy(() -> jdbc.update("INSERT INTO note_links (id, user_id, note_id, position, origin, target_type, target_key, created_at, updated_at) "
                + "VALUES (?, ?, ?, 0, 'EXPLICIT', 'MONTHLY_SUMMARY', '2026-13', ?, ?)", UUID.randomUUID(), userId, UUID.fromString(id), NOW, NOW))
                .isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_note_links_key_format");
        // nota e tag de outro usuário: a FK composta (note_id, user_id) também vale
        assertThatThrownBy(() -> jdbc.update("INSERT INTO note_tags (id, user_id, note_id, tag, created_at, updated_at) VALUES (?, ?, ?, 'x', ?, ?)", UUID.randomUUID(), otherUser,
                UUID.fromString(id), NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("fk_note_tags_note_id_user");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version) VALUES (?, ?, 'g.md', 'g.md', 'g', ?, ?, ?, 0)",
                UUID.randomUUID(), userId, "a".repeat(204_801), NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class).hasMessageContaining("ck_notes_body_size");
        assertThatThrownBy(() -> jdbc.update("INSERT INTO notes (id, user_id, path, path_key, title, body, created_at, updated_at, version) VALUES (?, ?, 'g.md', 'g.md', ' ', 'x', ?, ?, 0)",
                UUID.randomUUID(), userId, NOW, NOW)).isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    @DisplayName("tags: GET /v1/notes/tags conta só notas ativas e filtra por tag normalizada")
    void deveContarETags() throws Exception {
        createNote("a.md", "#revisao-mensal e #financas");
        createNote("b.md", "---\ntags: [Revisão Mensal]\n---\ncorpo");
        String gone = createNote("c.md", "#revisao-mensal #lixo").get("id").asString();
        trashNote(gone);
        MvcResult tags = mockMvc.perform(get("/v1/notes/tags")).andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
        JsonNode list = body(tags);
        assertThat(list.get(0).get("tag").asString()).isEqualTo("revisao-mensal");
        assertThat(list.get(0).get("count").asLong()).isEqualTo(2L);
        assertThat(list.size()).isEqualTo(2);
        assertThat(paths(listNotes("tag=revisao-mensal"))).containsExactlyInAnyOrder("a.md", "b.md");
        assertThat(paths(okBody(get("/v1/notes").queryParam("tag", "Revisão-Mensal")))).containsExactlyInAnyOrder("a.md", "b.md");
        assertThat(paths(listNotes("tag=inexistente"))).isEmpty();
        assertThat(paths(okBody(get("/v1/notes").queryParam("tag", "$$")))).isEmpty();
    }
}
