package com.moneylog.infra.note;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

abstract class NotesItBase extends E4ItBase {

    protected String notebookId;
    protected String goalId;
    protected String debtId;
    protected String invoiceId;

    @org.junit.jupiter.api.BeforeEach
    void cenarioDeNotas() throws Exception {
        String tech = category("Tecnologia", com.moneylog.core.category.domain.CategoryKind.EXPENSE);
        notebookId = created("{\"type\":\"CARD_PURCHASE\",\"amount\":\"1200.00\",\"date\":\"2026-10-10\",\"description\":\"Notebook\",\"cardId\":\"" + card
                + "\",\"categoryId\":\"" + tech + "\",\"installments\":6}").get("id").asString();
        goalId = createdBody(post("/v1/goals", "{\"name\":\"Reserva de emergência\",\"target\":\"20000.00\",\"priority\":\"HIGH\"}")).get("id").asString();
        debtId = createdBody(post("/v1/debts", "{\"personId\":\"" + maria + "\",\"direction\":\"PAYABLE\",\"principal\":\"200.00\","
                + "\"date\":\"2026-10-10\",\"description\":\"Almoço pago por Maria\"}")).get("id").asString();
        invoiceId = jdbc.queryForObject("SELECT id FROM credit_card_invoices WHERE user_id = ? LIMIT 1", String.class, userId);
    }

    protected MockHttpServletRequestBuilder delete(String url) {
        return MockMvcRequestBuilders.delete(url).header(HttpHeaders.AUTHORIZATION, bearer);
    }

    protected MockHttpServletRequestBuilder postNoBody(String url) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, bearer).contentType(MediaType.APPLICATION_JSON);
    }

    protected MockHttpServletRequestBuilder postNoBodyAs(String otherBearer, String url) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, otherBearer).contentType(MediaType.APPLICATION_JSON);
    }

    protected String otherUserBearer() {
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com"));
        return auth.bearer(other);
    }

    protected String json(String text) throws Exception {
        return objectMapper.writeValueAsString(text);
    }

    protected String noteJson(String path, String body) throws Exception {
        return "{\"path\":" + json(path) + ",\"body\":" + json(body) + "}";
    }

    protected String noteJson(String path, String body, String linksJson) throws Exception {
        return "{\"path\":" + json(path) + ",\"body\":" + json(body) + ",\"links\":" + linksJson + "}";
    }

    protected static String link(String type, String id) {
        return "{\"type\":\"" + type + "\",\"id\":\"" + id + "\"}";
    }

    protected static String monthLink(String key) {
        return "{\"type\":\"MONTHLY_SUMMARY\",\"key\":\"" + key + "\"}";
    }

    protected JsonNode createNote(String path, String body) throws Exception {
        return createdBody(post("/v1/notes", noteJson(path, body)));
    }

    protected JsonNode createNote(String path, String body, String linksJson) throws Exception {
        return createdBody(post("/v1/notes", noteJson(path, body, linksJson)));
    }

    protected JsonNode getNote(String id) throws Exception {
        return okBody(get("/v1/notes/" + id));
    }

    protected JsonNode editNote(String id, String body, long version) throws Exception {
        return okBody(put("/v1/notes/" + id, "{\"body\":" + json(body) + ",\"version\":" + version + "}"));
    }

    protected JsonNode moveNote(String id, String newPath, long version) throws Exception {
        return okBody(post("/v1/notes/" + id + "/move", "{\"newPath\":" + json(newPath) + ",\"version\":" + version + "}"));
    }

    protected JsonNode trashNote(String id) throws Exception {
        return okBody(postNoBody("/v1/notes/" + id + "/trash"));
    }

    protected JsonNode restoreNote(String id) throws Exception {
        return okBody(postNoBody("/v1/notes/" + id + "/restore"));
    }

    protected JsonNode setLinks(String id, String linksJson, long version) throws Exception {
        return okBody(put("/v1/notes/" + id + "/links", "{\"links\":" + linksJson + ",\"version\":" + version + "}"));
    }

    protected JsonNode listNotes(String query) throws Exception {
        return okBody(get("/v1/notes" + (query == null || query.isEmpty() ? "" : "?" + query)));
    }

    protected JsonNode search(String q) throws Exception {
        return okBody(get("/v1/notes").queryParam("q", q));
    }

    protected JsonNode backlinks(String id) throws Exception {
        return okBody(get("/v1/notes/" + id + "/backlinks"));
    }

    protected ResultActions expectError(MockHttpServletRequestBuilder request, int status, String code) throws Exception {
        return mockMvc.perform(request).andExpect(status().is(status))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath("$.code").value(code))
                .andExpect(ContractValidation.responseOnly());
    }

    protected String ids(JsonNode slice) {
        StringBuilder out = new StringBuilder();
        for (JsonNode item : slice.get("items")) {
            out.append(item.get("path").asString()).append(',');
        }
        return out.toString();
    }

    protected java.util.List<String> paths(JsonNode slice) {
        java.util.List<String> out = new java.util.ArrayList<>();
        for (JsonNode item : slice.get("items")) {
            out.add(item.get("path").asString());
        }
        return out;
    }

    protected JsonNode linkOf(JsonNode note, String type) {
        for (JsonNode link : note.get("links")) {
            if (link.get("type").asString().equals(type)) {
                return link;
            }
        }
        throw new AssertionError("sem vínculo " + type + " em " + note.get("links"));
    }

    protected MvcResult perform(MockHttpServletRequestBuilder request) throws Exception {
        return mockMvc.perform(request).andReturn();
    }
}
