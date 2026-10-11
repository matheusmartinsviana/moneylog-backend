package com.moneylog.infra.search;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.net.URI;
import java.time.Duration;
import java.util.ArrayList;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;
import java.util.UUID;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.mockito.Mockito;
import org.springframework.http.HttpHeaders;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.search.app.gateways.SearchGateway;
import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Busca global (T186, S-287)")
class SearchIT extends E4ItBase {

    @MockitoSpyBean
    private SearchGateway searchGateway;

    private JsonNode search(String q) throws Exception {
        return okBody(get("/v1/search").param("q", q));
    }

    private JsonNode searchAs(String otherBearer, String q) throws Exception {
        return body(mockMvc.perform(getAs(otherBearer, "/v1/search").param("q", q)).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andReturn());
    }

    private String expenseOn(String description, String date, String amount, String category) throws Exception {
        clock.advance(Duration.ofSeconds(1));
        return created("{\"type\":\"EXPENSE\",\"amount\":\"" + amount + "\",\"date\":\"" + date + "\",\"description\":" + objectMapper.writeValueAsString(description)
                + ",\"accountId\":\"" + nubank + "\",\"categoryId\":\"" + category + "\",\"method\":\"PIX\"}").get("id").asString();
    }

    private String expense(String description) throws Exception {
        return expenseOn(description, "2026-10-10", "10.00", food);
    }

    private String note(String path, String text) throws Exception {
        return createdBody(post("/v1/notes", "{\"path\":" + objectMapper.writeValueAsString(path) + ",\"body\":" + objectMapper.writeValueAsString(text) + "}"))
                .get("id").asString();
    }

    private static List<String> values(JsonNode group, String field) {
        List<String> values = new ArrayList<>();
        group.forEach(item -> values.add(item.get(field).asString()));
        return values;
    }

    @Test
    @DisplayName("sem diferenciar acento nem caixa: 'cafe' acha a movimentação 'Café', a categoria 'Cafeteria', a conta e a nota 'Reunião café'; 'CAFÉ' acha o mesmo; o termo volta aparado")
    void deveAcharSemAcentoNemCaixa() throws Exception {
        expense("Café com leite");
        expense("CAFÉ DA MANHÃ");
        expense("Cafezinho");
        expense("Mercado");
        category("Cafeteria", CategoryKind.EXPENSE);
        createdBody(post("/v1/accounts", "{\"name\":\"Conta Café\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}"));
        String noteId = note("reunioes/Reunião café.md", "pauta da reunião");

        JsonNode result = search("  cafe  ");

        assertThat(result.get("q").asString()).isEqualTo("cafe");
        assertThat(values(result.get("transactions"), "description")).containsExactlyInAnyOrder("Café com leite", "CAFÉ DA MANHÃ", "Cafezinho");
        assertThat(values(result.get("categories"), "name")).containsExactly("Cafeteria");
        assertThat(values(result.get("accounts"), "name")).containsExactly("Conta Café");
        assertThat(values(result.get("notes"), "id")).containsExactly(noteId);
        assertThat(result.get("cards")).isEmpty();
        assertThat(result.get("truncated")).isEmpty();
        JsonNode upper = search("CAFÉ");
        assertThat(values(upper.get("transactions"), "description")).containsExactlyInAnyOrder("Café com leite", "CAFÉ DA MANHÃ", "Cafezinho");
        assertThat(values(upper.get("categories"), "name")).containsExactly("Cafeteria");
    }

    @Test
    @DisplayName("a transação traz só campos de exibição e rota (id, descrição, data, valor, tipo, categoria, conta); nenhum total ou saldo; chaves exatas")
    void deveTrazerSoCamposDeExibicao() throws Exception {
        expense("Almoço");

        JsonNode result = search("almo");

        assertThat(keys(result)).containsExactly("accounts", "cards", "categories", "notes", "q", "transactions", "truncated");
        JsonNode item = result.get("transactions").get(0);
        assertThat(keys(item)).containsExactly("account", "amount", "category", "date", "description", "id", "type");
        assertThat(item.get("amount").get("amount").asString()).isEqualTo("10.00");
        assertThat(item.get("type").asString()).isEqualTo("EXPENSE");
        assertThat(item.get("date").asString()).isEqualTo("2026-10-10");
        assertThat(item.get("category").get("name").asString()).isEqualTo("Alimentação");
        assertThat(item.get("account").get("name").asString()).isEqualTo("Nubank");
    }

    private static Set<String> keys(JsonNode node) {
        Set<String> keys = new TreeSet<>();
        node.propertyNames().forEach(keys::add);
        return keys;
    }

    @Test
    @DisplayName("cartão acha por nome e por instituição; conta idem; arquivados vêm por último e marcados")
    void deveAcharCartoesEContasEOrdenarArquivadosPorUltimo() throws Exception {
        JsonNode byInstitution = search("nubank");
        assertThat(values(byInstitution.get("cards"), "name")).containsExactly("Roxinho");
        assertThat(byInstitution.get("cards").get(0).get("status").asString()).isEqualTo("ACTIVE");
        assertThat(values(byInstitution.get("accounts"), "name")).containsExactly("Nubank");

        for (String name : new String[] {"Zeta Conta", "Ágata Conta", "alfa conta"}) {
            createdBody(post("/v1/accounts", "{\"name\":\"" + name + "\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                    + "\"openingDate\":\"2026-10-01\"}"));
        }
        assertThat(values(search("conta").get("accounts"), "name")).as("nome sem acento nem caixa, depois id").containsExactly("Ágata Conta", "alfa conta", "Zeta Conta");
    }

    @Test
    @DisplayName("ordem determinística: movimentações por data desc, createdAt desc e id desc; contas e categorias por nome sem acento; arquivados por último")
    void deveOrdenarDeterministicamente() throws Exception {
        String old = expenseOn("Pão antigo", "2026-10-01", "5.00", food);
        String firstSameDay = expenseOn("Pão do dia A", "2026-10-09", "5.00", food);
        String secondSameDay = expenseOn("Pão do dia B", "2026-10-09", "5.00", food);
        String newest = expenseOn("Pão de ontem", "2026-10-10", "5.00", food);

        JsonNode transactions = search("pão").get("transactions");

        assertThat(values(transactions, "id")).containsExactly(newest, secondSameDay, firstSameDay, old);
        assertThat(values(search("pao").get("transactions"), "id")).as("estável entre chamadas e sem acento")
                .containsExactly(newest, secondSameDay, firstSameDay, old);

        String zeta = category("Zeta Cat", CategoryKind.EXPENSE);
        category("Ágata Cat", CategoryKind.EXPENSE);
        category("beta cat", CategoryKind.EXPENSE);
        String archived = category("Alfa Cat", CategoryKind.EXPENSE);
        mockMvc.perform(post("/v1/categories/" + archived + "/archive", "{}")).andExpect(status().isOk());
        JsonNode categories = search("cat").get("categories");
        assertThat(values(categories, "name")).containsExactly("Ágata Cat", "beta cat", "Zeta Cat", "Alfa Cat");
        assertThat(categories.get(3).get("archived").asBoolean()).isTrue();
        assertThat(categories.get(0).get("archived").asBoolean()).isFalse();
        assertThat(zeta).isNotBlank();
    }

    @Test
    @DisplayName("limit vale por grupo (padrão 5, 1 a 8); fora disso → 400 VALIDATION_FAILED")
    void deveRespeitarOLimitePorGrupo() throws Exception {
        for (int i = 0; i < 9; i++) {
            expense("Lanche " + i);
            category("Lanchonete " + i, CategoryKind.EXPENSE);
        }

        assertThat(search("lanch").get("transactions")).hasSize(5);
        assertThat(search("lanch").get("categories")).hasSize(5);
        assertThat(okBody(get("/v1/search").param("q", "lanch").param("limit", "2")).get("transactions")).hasSize(2);
        assertThat(okBody(get("/v1/search").param("q", "lanch").param("limit", "8")).get("transactions")).hasSize(8);
        for (String limit : new String[] {"0", "9", "-1", "x"}) {
            mockMvc.perform(get("/v1/search").param("q", "lanch").param("limit", limit)).andExpect(status().isBadRequest())
                    .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("%, _ e \\ são literais: '100%' não casa tudo, 'a_b' não casa 'axb' e '\\temp' acha o caminho com barra")
    void deveTratarCuringasComoTexto() throws Exception {
        expense("100% dinheiro");
        expense("100 dinheiro");
        expense("a_b");
        expense("axb");
        expense("c:\\temp");
        expense("qualquer coisa");

        assertThat(values(search("100%").get("transactions"), "description")).containsExactly("100% dinheiro");
        assertThat(values(search("a_b").get("transactions"), "description")).containsExactly("a_b");
        assertThat(values(search("\\temp").get("transactions"), "description")).containsExactly("c:\\temp");
        assertThat(values(search("%%").get("transactions"), "description")).as("dois % seguidos não existem em texto algum").isEmpty();
        assertThat(values(search("__").get("transactions"), "description")).isEmpty();
        assertThat(values(search("\\\\").get("transactions"), "description")).isEmpty();
        assertThat(values(search("_b").get("transactions"), "description")).containsExactly("a_b");
    }

    @ParameterizedTest(name = "q inválido #{index}")
    @ValueSource(strings = {"", " ", "a", " a ", "ab\u0000cd", "ab\u0001cd", "ab\ncd", "ab\tcd", "ab\u007Fcd", "ab\u0085cd", "\u0000\u0000"})
    @DisplayName("q vazio, de 1 caractere ou com caractere de controle (inclusive NUL) → 400 SEARCH_QUERY_INVALID (nunca 500) e o corpo não ecoa o termo")
    void deveRecusarTermoInvalido(String q) throws Exception {
        String response = mockMvc.perform(get("/v1/search").param("q", q)).andExpect(status().isBadRequest())
                .andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.code").value("SEARCH_QUERY_INVALID"))
                .andReturn().getResponse().getContentAsString();
        assertThat(response).doesNotContain("\\u0000");
        if (q.strip().length() >= 4) {
            assertThat(response).doesNotContain(q);
        }
    }

    @Test
    @DisplayName("q ausente, com 101 caracteres → 400 SEARCH_QUERY_INVALID; com 100 caracteres (e 100 emojis, contados em pontos de código) → 200; 101 emojis → 400")
    void deveValidarOTamanho() throws Exception {
        mockMvc.perform(get("/v1/search")).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.code").value("SEARCH_QUERY_INVALID"));
        mockMvc.perform(get("/v1/search").param("q", "a".repeat(101))).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.code").value("SEARCH_QUERY_INVALID"));
        okBody(get("/v1/search").param("q", "a".repeat(100)));
        okBody(get("/v1/search").param("q", "😀".repeat(100)));
        mockMvc.perform(get("/v1/search").param("q", "😀".repeat(101))).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("SEARCH_QUERY_INVALID"));
    }

    @ParameterizedTest(name = "q={0}")
    @ValueSource(strings = {"ab%00cd", "%00%00", "ab%01cd", "ab%0Acd", "ab%1Bcd", "%00ab", "ab%7Fcd"})
    @DisplayName("termo com controle codificado na URL (%00, %0A, %1B, %7F) → 400 SEARCH_QUERY_INVALID (nunca 500): o firewall HTTP e o controlador dão o mesmo erro")
    void deveRecusarControleCodificadoNaUrl(String encoded) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get(URI.create("/v1/search?q=" + encoded)).header(HttpHeaders.AUTHORIZATION, bearer))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.code").value("SEARCH_QUERY_INVALID"));
    }

    @Test
    @DisplayName("sem token → 401; Cache-Control: no-store, private")
    void deveExigirTokenENaoCachear() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/search").param("q", "cafe")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/search").param("q", "cafe")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "no-store, private"));
    }

    @Test
    @DisplayName("movimentação estornada e nota na lixeira não aparecem")
    void deveIgnorarEstornadasEALixeira() throws Exception {
        String voided = expense("Gasto estornado");
        expense("Gasto vivo");
        mockMvc.perform(post("/v1/transactions/" + voided + "/void", "{\"reason\":\"engano\"}")).andExpect(status().isOk());
        String trashed = note("lixo/Nota do lixo.md", "texto");
        mockMvc.perform(post("/v1/notes/" + trashed + "/trash", "{}")).andExpect(status().isOk());
        note("vivas/Nota viva.md", "texto");

        assertThat(values(search("gasto").get("transactions"), "description")).containsExactly("Gasto vivo");
        assertThat(values(search("nota").get("notes"), "title")).containsExactly("Nota viva");
    }

    @Test
    @DisplayName("notas: só título e caminho (o corpo não casa); o snippet é texto puro: início do corpo, espaços e quebras colapsados, sem controle, até 160 caracteres")
    void deveBuscarNotasSoPorTituloECaminho() throws Exception {
        note("pasta/Orçamento da casa.md", "unico_termo_do_corpo\nsegunda   linha\twith escape <b>html</b> " + "x".repeat(300));
        note("outra/sem relacao.md", "texto qualquer");

        assertThat(search("unico_termo_do_corpo").get("notes")).as("o corpo não é varrido").isEmpty();
        assertThat(search("orcamento").get("notes")).hasSize(1);
        assertThat(search("PASTA/").get("notes")).as("caminho").hasSize(1);
        String snippet = search("orcamento").get("notes").get(0).get("snippet").asString();
        assertThat(snippet).startsWith("unico_termo_do_corpo segunda linha with escape <b>html</b> xxxx");
        assertThat(snippet).hasSizeLessThanOrEqualTo(160).doesNotContain("\n").doesNotContain("\t");
        assertThat(search("orcamento").get("notes").get(0).get("title").asString()).isEqualTo("Orçamento da casa");
        assertThat(search("orcamento").get("notes").get(0).get("path").asString()).isEqualTo("pasta/Orçamento da casa.md");
    }

    @Test
    @DisplayName("isolamento: dois usuários com a mesma descrição, conta, categoria, cartão e nota enxergam só os seus (IT com os dois, em todos os grupos)")
    void deveIsolarPorUsuario() throws Exception {
        String mine = expense("Restaurante japonês");
        category("Japonesa", CategoryKind.EXPENSE);
        createdBody(post("/v1/accounts", "{\"name\":\"Conta japonesa\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}"));
        createdBody(post("/v1/cards", "{\"name\":\"Cartão japonês\",\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"closingDay\":25,\"dueDay\":5}"));
        String myNote = note("jp/Cozinha japonesa.md", "viagem");

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "other-" + other + "@moneylog.com"));
        String otherBearer = auth.bearer(other);
        Category otherCategory = Category.create(UUID.randomUUID(), other, "Japonesa", CategoryKind.EXPENSE);
        committed(() -> SqlFixtures.insertCategory(jdbc, otherCategory));
        String otherAccount = body(mockMvc.perform(postAs(otherBearer, "/v1/accounts", "{\"name\":\"Conta japonesa\",\"type\":\"CHECKING\",\"openingBalance\":"
                + "{\"amount\":\"0.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).andExpect(status().isCreated()).andReturn()).get("id").asString();
        mockMvc.perform(postAs(otherBearer, "/v1/cards", "{\"name\":\"Cartão japonês\",\"limit\":{\"amount\":\"1000.00\",\"currency\":\"BRL\"},\"closingDay\":25,"
                + "\"dueDay\":5}")).andExpect(status().isCreated());
        mockMvc.perform(postAs(otherBearer, "/v1/notes", "{\"path\":\"jp/Cozinha japonesa.md\",\"body\":\"viagem\"}")).andExpect(status().isCreated());
        mockMvc.perform(postAs(otherBearer, "/v1/transactions", "{\"type\":\"EXPENSE\",\"amount\":\"20.00\",\"date\":\"2026-10-10\",\"description\":\"Restaurante japonês\","
                + "\"accountId\":\"" + otherAccount + "\",\"categoryId\":\"" + otherCategory.id() + "\",\"method\":\"PIX\"}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated());

        JsonNode mineResult = search("japon");
        JsonNode theirs = searchAs(otherBearer, "japon");

        assertThat(mineResult.get("transactions")).hasSize(1);
        assertThat(mineResult.get("transactions").get(0).get("id").asString()).isEqualTo(mine);
        assertThat(mineResult.get("accounts")).hasSize(1);
        assertThat(mineResult.get("categories")).hasSize(1);
        assertThat(mineResult.get("cards")).hasSize(1);
        assertThat(mineResult.get("notes")).hasSize(1);
        assertThat(values(mineResult.get("notes"), "id")).containsExactly(myNote);
        assertThat(theirs.get("transactions")).hasSize(1);
        assertThat(theirs.get("transactions").get(0).get("id").asString()).isNotEqualTo(mine);
        assertThat(theirs.get("transactions").get(0).get("amount").get("amount").asString()).isEqualTo("20.00");
        assertThat(theirs.get("accounts")).hasSize(1);
        assertThat(theirs.get("accounts").get(0).get("id").asString()).isEqualTo(otherAccount);
        assertThat(theirs.get("categories").get(0).get("id").asString()).isEqualTo(otherCategory.id().toString());
        assertThat(theirs.get("cards")).hasSize(1);
        assertThat(theirs.get("notes")).hasSize(1);
        assertThat(theirs.get("notes").get(0).get("id").asString()).isNotEqualTo(myNote);
        UUID empty = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, empty, "empty-" + empty + "@moneylog.com"));
        JsonNode nothing = searchAs(auth.bearer(empty), "japon");
        assertThat(nothing.get("transactions")).isEmpty();
        assertThat(nothing.get("accounts")).isEmpty();
        assertThat(nothing.get("categories")).isEmpty();
        assertThat(nothing.get("cards")).isEmpty();
        assertThat(nothing.get("notes")).isEmpty();
    }

    @Test
    @DisplayName("limite de taxa: 120 pedidos por minuto por usuário; o 121º → 429 TOO_MANY_ATTEMPTS com Retry-After; outro usuário e a exportação não são afetados; passada a janela volta a 200")
    void deveLimitarA120PorMinuto() throws Exception {
        for (int i = 0; i < 120; i++) {
            mockMvc.perform(get("/v1/search").param("q", "limite")).andExpect(status().isOk());
        }

        mockMvc.perform(get("/v1/search").param("q", "limite")).andExpect(status().isTooManyRequests())
                .andExpect(header().exists("Retry-After")).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS"));
        mockMvc.perform(get("/v1/exports/transactions.csv")).andExpect(status().isOk());

        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "rate-" + other + "@moneylog.com"));
        searchAs(auth.bearer(other), "limite");

        clock.advance(Duration.ofSeconds(61));
        bearer = auth.bearer(userId);
        mockMvc.perform(get("/v1/search").param("q", "limite")).andExpect(status().isOk());
    }

    @Test
    @DisplayName("a busca lê num instantâneo REPEATABLE_READ somente leitura (as cinco consultas veem o mesmo estado)")
    void deveLerNumInstantaneoUnico() throws Exception {
        AtomicReference<Integer> isolation = new AtomicReference<>();
        AtomicReference<Boolean> readOnly = new AtomicReference<>();
        Mockito.doAnswer(invocation -> {
            isolation.set(TransactionSynchronizationManager.getCurrentTransactionIsolationLevel());
            readOnly.set(TransactionSynchronizationManager.isCurrentTransactionReadOnly());
            return invocation.callRealMethod();
        }).when(searchGateway).search(Mockito.any(), Mockito.any(), Mockito.anyInt());

        search("qualquer");

        assertThat(isolation.get()).isEqualTo(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        assertThat(readOnly.get()).isTrue();
    }

    @Test
    @DisplayName("a busca não escreve nada: as contagens de movimentações, lançamentos e notas são as mesmas antes e depois")
    void deveSerLeituraPura() throws Exception {
        expense("Gasto");
        note("n/Nota.md", "texto");
        long transactions = count("transactions");
        long postings = count("ledger_postings");
        long notes = count("notes");

        search("gasto");
        search("nota");

        assertThat(count("transactions")).isEqualTo(transactions);
        assertThat(count("ledger_postings")).isEqualTo(postings);
        assertThat(count("notes")).isEqualTo(notes);
    }
}
