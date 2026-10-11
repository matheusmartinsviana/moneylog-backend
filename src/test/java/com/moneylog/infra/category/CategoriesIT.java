package com.moneylog.infra.category;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.auth.app.OwnerCreated;
import com.moneylog.core.category.app.SystemCategories;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@DisplayName("Categorias (T23)")
class CategoriesIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private ApplicationEventPublisher publisher;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private void committed(Runnable work) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> work.run());
    }

    private MockHttpServletRequestBuilder get(String url) {
        return MockMvcRequestBuilders.get(url).header(HttpHeaders.AUTHORIZATION, auth.ownerBearer());
    }

    private MockHttpServletRequestBuilder post(String url, String json) {
        return MockMvcRequestBuilders.post(url).header(HttpHeaders.AUTHORIZATION, auth.ownerBearer())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private MockHttpServletRequestBuilder put(String url, String json) {
        return MockMvcRequestBuilders.put(url).header(HttpHeaders.AUTHORIZATION, auth.ownerBearer())
                .contentType(MediaType.APPLICATION_JSON).content(json);
    }

    private static String unique(String base) {
        return base + " " + UUID.randomUUID().toString().substring(0, 8);
    }

    private JsonNode json(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private JsonNode createCategory(String name, String kind) throws Exception {
        return json(mockMvc.perform(post("/v1/categories", "{\"name\":\"" + name + "\",\"kind\":\"" + kind + "\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andReturn());
    }

    private JsonNode list(String query) throws Exception {
        return json(mockMvc.perform(get("/v1/categories" + query)).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
    }

    @Nested
    @DisplayName("CA1 e CA2: seed das categorias de sistema")
    class Seed {

        @Test
        @DisplayName("o dono criado no boot ganha 13 de despesa e 3 de receita de sistema, sem investimento")
        void deveTerSeedDoDono() {
            UUID owner = auth.ownerId();

            assertThat(jdbc.queryForObject("select count(*) from categories where user_id = ? and system and kind = 'EXPENSE'",
                    Integer.class, owner)).isEqualTo(13);
            assertThat(jdbc.queryForObject("select count(*) from categories where user_id = ? and system and kind = 'INCOME'",
                    Integer.class, owner)).isEqualTo(3);
            assertThat(jdbc.queryForList("select system_key from categories where user_id = ? and system", String.class, owner))
                    .doesNotHaveDuplicates().noneMatch(key -> key.contains("INVEST"))
                    .containsExactlyInAnyOrderElementsOf(SystemCategories.ALL.stream().map(SystemCategories.Definition::key).toList());
            assertThat(jdbc.queryForList("select name from categories where user_id = ? and system and kind = 'EXPENSE'", String.class, owner))
                    .noneMatch(name -> name.toLowerCase().contains("investiment"));
        }

        @Test
        @DisplayName("publicar OwnerCreated de novo não duplica nada; um usuário novo recebe as 16")
        void deveSerIdempotente() {
            UUID owner = auth.ownerId();
            Integer before = jdbc.queryForObject("select count(*) from categories where user_id = ?", Integer.class, owner);

            publisher.publishEvent(new OwnerCreated(owner, "owner@moneylog.com"));

            assertThat(jdbc.queryForObject("select count(*) from categories where user_id = ?", Integer.class, owner)).isEqualTo(before);

            UUID other = UUID.randomUUID();
            committed(() -> SqlFixtures.insertUser(jdbc, other, "seed-" + other + "@moneylog.com"));
            publisher.publishEvent(new OwnerCreated(other, "seed@moneylog.com"));
            publisher.publishEvent(new OwnerCreated(other, "seed@moneylog.com"));

            assertThat(jdbc.queryForObject("select count(*) from categories where user_id = ? and system", Integer.class, other))
                    .isEqualTo(16);
        }
    }

    @Test
    @DisplayName("CA3: GET ?kind=EXPENSE traz só as de despesa não arquivadas em ordem alfabética; includeArchived traz as arquivadas")
    void deveListarPorTipoEmOrdem() throws Exception {
        String archivedName = unique("Arq");
        JsonNode archived = createCategory(archivedName, "EXPENSE");
        mockMvc.perform(post("/v1/categories/" + archived.get("id").asString() + "/archive", "")).andExpect(status().isOk());

        JsonNode expenses = list("?kind=EXPENSE");

        List<String> names = expenses.valueStream().map(c -> c.get("name").asString()).toList();
        assertThat(expenses.valueStream()).allMatch(c -> c.get("kind").asString().equals("EXPENSE") && !c.get("archived").asBoolean());
        assertThat(names).doesNotContain(archivedName).isSortedAccordingTo(String.CASE_INSENSITIVE_ORDER.thenComparing(String::compareTo));
        assertThat(names).contains("Alimentação", "Outros");
        assertThat(list("?kind=EXPENSE&includeArchived=true").valueStream().map(c -> c.get("name").asString())).contains(archivedName);
        assertThat(list("?kind=INCOME").valueStream().map(c -> c.get("name").asString())).contains("Salário")
                .doesNotContain("Alimentação");
        assertThat(list("").size()).isGreaterThanOrEqualTo(16);
    }

    @Test
    @DisplayName("tipo fora do enum é 400 VALIDATION_FAILED")
    void deveRecusarTipoInvalido() throws Exception {
        mockMvc.perform(get("/v1/categories?kind=NOPE")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED")).andExpect(jsonPath("$.errors[0].field").value("kind"))
                .andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA4: POST cria com 201 + Location; mesmo nome (sem diferenciar maiúsculas) no mesmo tipo é 409; no outro tipo, aceita")
    void deveCriarEImpedirNomeRepetido() throws Exception {
        String name = unique("Pets");

        MvcResult created = mockMvc.perform(post("/v1/categories", "{\"name\":\"" + name + "\",\"kind\":\"EXPENSE\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.name").value(name)).andExpect(jsonPath("$.system").value(false))
                .andExpect(jsonPath("$.archived").value(false)).andExpect(jsonPath("$.version").value(0)).andReturn();
        String id = json(created).get("id").asString();
        assertThat(created.getResponse().getHeader("Location")).isEqualTo("/v1/categories/" + id);

        mockMvc.perform(post("/v1/categories", "{\"name\":\"" + name.toLowerCase() + "\",\"kind\":\"EXPENSE\"}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories", "{\"name\":\"" + name + "\",\"kind\":\"INCOME\"}")).andExpect(status().isCreated())
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("corpo inválido (sem nome, nome longo, tipo desconhecido) é 400 VALIDATION_FAILED")
    void deveValidarOCorpo() throws Exception {
        mockMvc.perform(post("/v1/categories", "{\"kind\":\"EXPENSE\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("name")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/categories", "{\"name\":\"" + "x".repeat(61) + "\",\"kind\":\"EXPENSE\"}"))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.errors[0].code").value("SIZE"))
                .andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/categories", "{\"name\":\"X\",\"kind\":\"OUTRO\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].code").value("INVALID_FORMAT")).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/categories", "{\"name\":\"X\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field").value("kind")).andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("CA5: categoria de sistema pode ser renomeada e arquivada; PUT com versão antiga é 409; não existe DELETE (405)")
    void deveRenomearEArquivarCategoriaDeSistema() throws Exception {
        JsonNode tecnologia = list("?kind=EXPENSE").valueStream().filter(c -> c.get("name").asString().equals("Tecnologia")).findFirst().orElseThrow();
        String id = tecnologia.get("id").asString();
        long version = tecnologia.get("version").asLong();
        String renamed = unique("Tec");

        JsonNode afterRename = json(mockMvc.perform(put("/v1/categories/" + id, "{\"name\":\"" + renamed + "\",\"version\":" + version + "}"))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.system").value(true)).andReturn());
        assertThat(afterRename.get("version").asLong()).isEqualTo(version + 1);

        mockMvc.perform(put("/v1/categories/" + id, "{\"name\":\"Outra\",\"version\":" + version + "}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories/" + id + "/archive", "")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.archived").value(true));
        mockMvc.perform(post("/v1/categories/" + id + "/archive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_ALREADY_ARCHIVED")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories/" + id + "/unarchive", "")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.archived").value(false));
        mockMvc.perform(post("/v1/categories/" + id + "/unarchive", "")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_ARCHIVED"));
        mockMvc.perform(put("/v1/categories/" + id, "{\"name\":\"Tecnologia\",\"version\":" + (version + 3) + "}")).andExpect(status().isOk());

        mockMvc.perform(delete("/v1/categories/" + id).header(HttpHeaders.AUTHORIZATION, auth.ownerBearer()))
                .andExpect(status().isMethodNotAllowed()).andExpect(jsonPath("$.code").value("METHOD_NOT_ALLOWED"))
                .andExpect(ContractValidation.responseOnly());
    }

    @Test
    @DisplayName("renomear para o nome de outra ativa é 409 CATEGORY_NAME_TAKEN")
    void deveRecusarRenomearParaNomeRepetido() throws Exception {
        String first = unique("Uma");
        createCategory(first, "EXPENSE");
        JsonNode second = createCategory(unique("Outra"), "EXPENSE");

        mockMvc.perform(put("/v1/categories/" + second.get("id").asString(), "{\"name\":\"" + first.toUpperCase() + "\",\"version\":0}"))
                .andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN"))
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("CA6: categoria de outro usuário responde 404 CATEGORY_NOT_FOUND em todas as rotas e não aparece na lista")
    void deveIsolarPorUsuario() throws Exception {
        UUID other = UUID.randomUUID();
        UUID categoryId = UUID.randomUUID();
        committed(() -> {
            SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@moneylog.com");
            jdbc.update("""
                    INSERT INTO categories (id, user_id, name, kind, system, archived, created_at, updated_at, version)
                    VALUES (?, ?, 'Segredo', 'EXPENSE', false, false, now(), now(), 0)""", categoryId, other);
        });

        mockMvc.perform(put("/v1/categories/" + categoryId, "{\"name\":\"Invadida\",\"version\":0}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories/" + categoryId + "/archive", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/categories/" + categoryId + "/unarchive", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND")).andExpect(ContractValidation.conforms());
        assertThat(list("?includeArchived=true").valueStream().map(c -> c.get("name").asString())).doesNotContain("Segredo");
        assertThat(jdbc.queryForObject("select name from categories where id = ?", String.class, categoryId)).isEqualTo("Segredo");
        createCategory("Segredo", "EXPENSE");
    }

    @Test
    @DisplayName("CA7: desarquivar com nome já usado por uma ativa criada depois é 409 e ela continua arquivada")
    void deveRecusarDesarquivarComNomeRepetido() throws Exception {
        String name = unique("Pets");
        JsonNode first = createCategory(name, "EXPENSE");
        mockMvc.perform(post("/v1/categories/" + first.get("id").asString() + "/archive", "")).andExpect(status().isOk());
        createCategory(name.toLowerCase(), "EXPENSE");

        mockMvc.perform(post("/v1/categories/" + first.get("id").asString() + "/unarchive", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("CATEGORY_NAME_TAKEN")).andExpect(ContractValidation.conforms());

        assertThat(jdbc.queryForObject("select archived from categories where id = ?", Boolean.class,
                UUID.fromString(first.get("id").asString()))).isTrue();
    }

    @Test
    @DisplayName("sem token as rotas respondem 401 UNAUTHENTICATED")
    void deveExigirToken() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/categories")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED")).andExpect(header().exists("X-Trace-Id"))
                .andExpect(ContractValidation.responseOnly());
    }
}
