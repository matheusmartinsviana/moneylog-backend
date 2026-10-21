package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Tokens de lançamento rápido: gestão (T3)")
class QuickEntryTokenManagementIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private UUID userId;
    private String bearer;
    private String walletId;

    @BeforeEach
    void novoUsuario() throws Exception {
        userId = newUser();
        bearer = auth.bearer(userId);
        walletId = createAccount(bearer, "Carteira", "WALLET");
    }

    private UUID newUser() {
        UUID id = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> SqlFixtures.insertUser(jdbc, id, "qt-" + id + "@example.com"));
        return id;
    }

    private MockHttpServletRequestBuilder json(MockHttpServletRequestBuilder builder, String token, String body) {
        return builder.header(HttpHeaders.AUTHORIZATION, token).contentType(MediaType.APPLICATION_JSON).content(body);
    }

    private JsonNode read(MvcResult result) throws Exception {
        return objectMapper.readTree(result.getResponse().getContentAsString());
    }

    private String createAccount(String token, String name, String type) throws Exception {
        String body = "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"},"
                + "\"openingDate\":\"2026-10-01\"}";
        return read(mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/accounts"), token, body)).andExpect(status().isCreated()).andReturn())
                .get("id").asString();
    }

    private MvcResult createToken(String token, String accountId, Integer days) throws Exception {
        String body = "{\"label\":\"iPhone\",\"accountId\":\"" + accountId + "\"" + (days == null ? "" : ",\"expiresInDays\":" + days) + "}";
        return mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), token, body)).andReturn();
    }

    private JsonNode createOk() throws Exception {
        MvcResult result = createToken(bearer, walletId, null);
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        return read(result);
    }

    private MockHttpServletRequestBuilder withBearer(MockHttpServletRequestBuilder builder, String token) {
        return builder.header(HttpHeaders.AUTHORIZATION, token);
    }

    @Test
    @DisplayName("cria com 201, Location, no-store e token mlq_; no banco só prefixo e hash; segredo fora de colunas e de logs")
    void deveCriar(CapturedOutput output) throws Exception {
        MvcResult result = createToken(bearer, walletId, 30);

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/quick-entry-tokens").header(HttpHeaders.AUTHORIZATION, bearer)).andReturn();
        assertThat(result.getResponse().getStatus()).isEqualTo(201);
        JsonNode body = read(result);
        String secret = body.get("token").asString();
        assertThat(secret).matches("^mlq_[A-Za-z0-9]{8}_[A-Za-z0-9_-]{43}$");
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/quick-entry-tokens/" + body.get("id").asString());
        assertThat(result.getResponse().getHeader("Cache-Control")).contains("no-store");
        assertThat(body.get("status").asString()).isEqualTo("ACTIVE");
        assertThat(body.get("accountName").asString()).isEqualTo("Carteira");
        assertThat(body.get("prefix").asString()).isEqualTo(secret.substring(4, 12));

        Map<String, Object> row = jdbc.queryForMap("select * from quick_entry_tokens where id = ?", UUID.fromString(body.get("id").asString()));
        assertThat(row.get("token_hash")).isEqualTo(QuickEntryTokenSecret.hashOf(secret));
        assertThat(row.get("prefix")).isEqualTo(body.get("prefix").asString());
        assertThat(row.values().stream().map(String::valueOf)).noneMatch(value -> value.contains(secret));
        assertThat(output.getAll()).doesNotContain(secret).contains("event=quick_token_created");
    }

    @Test
    @DisplayName("a criação confere com o contrato")
    void deveConferirComContrato() throws Exception {
        mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), bearer,
                        "{\"label\":\"iPhone\",\"accountId\":\"" + walletId + "\",\"expiresInDays\":30}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("a lista não traz o token e traz prefix, status, expiresAt, lastUsedAt e lastUsedIp, recentes primeiro, e confere com o contrato")
    void deveListar() throws Exception {
        JsonNode first = createOk();
        JsonNode second = createOk();

        mockMvc.perform(withBearer(MockMvcRequestBuilders.get("/v1/quick-entry-tokens"), bearer)).andExpect(status().isOk())
                .andExpect(header().string("Cache-Control", org.hamcrest.Matchers.containsString("no-store")))
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].id").value(second.get("id").asString()))
                .andExpect(jsonPath("$[1].id").value(first.get("id").asString()))
                .andExpect(jsonPath("$[0].token").doesNotExist())
                .andExpect(jsonPath("$[0].prefix").value(second.get("prefix").asString()))
                .andExpect(jsonPath("$[0].status").value("ACTIVE"))
                .andExpect(jsonPath("$[0].lastUsedAt").hasJsonPath())
                .andExpect(jsonPath("$[0].lastUsedIp").hasJsonPath())
                .andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("conta de outro usuário é 404 ACCOUNT_NOT_FOUND; investimento e arquivada são 422; nada é gravado")
    void deveRecusarContas() throws Exception {
        UUID other = newUser();
        String otherAccount = createAccount(auth.bearer(other), "Dele", "CHECKING");
        String investment = createAccount(bearer, "Corretora", "INVESTMENT");
        String archived = createAccount(bearer, "Velha", "SAVINGS");
        mockMvc.perform(withBearer(MockMvcRequestBuilders.post("/v1/accounts/" + archived + "/archive"), bearer)).andExpect(status().isOk());

        mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), bearer,
                        "{\"label\":\"x\",\"accountId\":\"" + otherAccount + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), bearer,
                        "{\"label\":\"x\",\"accountId\":\"" + UUID.randomUUID() + "\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        for (String account : List.of(investment, archived)) {
            mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), bearer,
                            "{\"label\":\"x\",\"accountId\":\"" + account + "\"}"))
                    .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("QUICK_ACCOUNT_NOT_ALLOWED"))
                    .andExpect(ContractValidation.conforms());
        }
        assertThat(jdbc.queryForObject("select count(*) from quick_entry_tokens where user_id = ?", Integer.class, userId)).isZero();
    }

    @Test
    @DisplayName("entrada inválida é 400: rótulo vazio, validade 0 e 91, conta ausente")
    void deveValidarEntrada() throws Exception {
        for (String body : List.of("{\"label\":\"\",\"accountId\":\"" + walletId + "\"}",
                "{\"label\":\"x\",\"accountId\":\"" + walletId + "\",\"expiresInDays\":0}",
                "{\"label\":\"x\",\"accountId\":\"" + walletId + "\",\"expiresInDays\":91}", "{\"label\":\"x\"}")) {
            mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), bearer, body)).andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        }
    }

    @Test
    @DisplayName("o quarto token ativo é 409 QUICK_TOKEN_LIMIT_REACHED; revogar um libera a vaga")
    void deveLimitarTokensAtivos() throws Exception {
        JsonNode first = createOk();
        createOk();
        createOk();

        MvcResult blocked = createToken(bearer, walletId, null);
        assertThat(blocked.getResponse().getStatus()).isEqualTo(409);
        assertThat(read(blocked).get("code").asString()).isEqualTo("QUICK_TOKEN_LIMIT_REACHED");
        mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), bearer,
                        "{\"label\":\"x\",\"accountId\":\"" + walletId + "\"}")).andExpect(ContractValidation.conforms());

        mockMvc.perform(withBearer(MockMvcRequestBuilders.post("/v1/quick-entry-tokens/" + first.get("id").asString() + "/revoke"), bearer))
                .andExpect(status().isOk());
        assertThat(createToken(bearer, walletId, null).getResponse().getStatus()).isEqualTo(201);
    }

    @Test
    @DisplayName("duas criações simultâneas com 2 ativos resultam em no máximo 3 ativos")
    void deveSerializarCriacoesConcorrentes() throws Exception {
        createOk();
        createOk();
        ExecutorService pool = Executors.newFixedThreadPool(6);
        try {
            List<Future<Integer>> futures = new ArrayList<>();
            for (int i = 0; i < 6; i++) {
                Callable<Integer> call = () -> createToken(bearer, walletId, null).getResponse().getStatus();
                futures.add(pool.submit(call));
            }
            int created = 0;
            int conflicts = 0;
            for (Future<Integer> future : futures) {
                int code = future.get();
                if (code == 201) {
                    created++;
                } else if (code == 409) {
                    conflicts++;
                }
            }
            assertThat(created).isEqualTo(1);
            assertThat(conflicts).isEqualTo(5);
        } finally {
            pool.shutdownNow();
        }
        assertThat(jdbc.queryForObject("select count(*) from quick_entry_tokens where user_id = ? and revoked_at is null", Integer.class, userId))
                .isEqualTo(3);
    }

    @Test
    @DisplayName("revogar duas vezes devolve REVOKED com o revokedAt da primeira; log e sem segredo")
    void deveRevogarComIdempotencia(CapturedOutput output) throws Exception {
        JsonNode created = createOk();
        String url = "/v1/quick-entry-tokens/" + created.get("id").asString() + "/revoke";

        JsonNode first = read(mockMvc.perform(withBearer(MockMvcRequestBuilders.post(url), bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED")).andExpect(jsonPath("$.revokedReason").value("USER"))
                .andExpect(ContractValidation.conforms()).andReturn());
        JsonNode second = read(mockMvc.perform(withBearer(MockMvcRequestBuilders.post(url), bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REVOKED")).andReturn());

        assertThat(second.get("revokedAt")).isEqualTo(first.get("revokedAt"));
        assertThat(output.getAll()).contains("event=quick_token_revoked").doesNotContain(created.get("token").asString());
    }

    @Test
    @DisplayName("renomear devolve o token com o novo rótulo; token de outro usuário é 404 QUICK_TOKEN_NOT_FOUND em renomear e revogar")
    void deveIsolarPorUsuario() throws Exception {
        JsonNode created = createOk();
        String id = created.get("id").asString();
        String stranger = auth.bearer(newUser());

        mockMvc.perform(json(MockMvcRequestBuilders.put("/v1/quick-entry-tokens/" + id), bearer, "{\"label\":\"Novo nome\"}"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.label").value("Novo nome")).andExpect(jsonPath("$.token").doesNotExist())
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(json(MockMvcRequestBuilders.put("/v1/quick-entry-tokens/" + id), stranger, "{\"label\":\"Invasor\"}"))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUICK_TOKEN_NOT_FOUND")).andExpect(ContractValidation.conforms());
        mockMvc.perform(withBearer(MockMvcRequestBuilders.post("/v1/quick-entry-tokens/" + id + "/revoke"), stranger))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("QUICK_TOKEN_NOT_FOUND"));
        mockMvc.perform(withBearer(MockMvcRequestBuilders.get("/v1/quick-entry-tokens"), stranger)).andExpect(jsonPath("$.length()").value(0));
        assertThat(jdbc.queryForObject("select label from quick_entry_tokens where id = ?", String.class, UUID.fromString(id))).isEqualTo("Novo nome");
    }

    @Test
    @DisplayName("token mlq_ não abre nenhuma rota de gestão: 401")
    void tokenMlqNaoGerencia() throws Exception {
        JsonNode created = createOk();
        String mlq = "Bearer " + created.get("token").asString();
        String id = created.get("id").asString();

        mockMvc.perform(withBearer(MockMvcRequestBuilders.get("/v1/quick-entry-tokens"), mlq)).andExpect(status().isUnauthorized());
        mockMvc.perform(json(MockMvcRequestBuilders.post("/v1/quick-entry-tokens"), mlq, "{\"label\":\"x\",\"accountId\":\"" + walletId + "\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(json(MockMvcRequestBuilders.put("/v1/quick-entry-tokens/" + id), mlq, "{\"label\":\"x\"}"))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(withBearer(MockMvcRequestBuilders.post("/v1/quick-entry-tokens/" + id + "/revoke"), mlq))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("sem credencial é 401 e não existe DELETE")
    void deveExigirSessao() throws Exception {
        String id = createOk().get("id").asString();

        mockMvc.perform(MockMvcRequestBuilders.get("/v1/quick-entry-tokens")).andExpect(status().isUnauthorized());
        mockMvc.perform(withBearer(MockMvcRequestBuilders.delete("/v1/quick-entry-tokens/" + id), bearer))
                .andExpect(status().is4xxClientError());
    }

    @Test
    @DisplayName("o dono desativa o usuário: todos os tokens ativos viram REVOKED/USER_DISABLED; reativar não os ressuscita")
    void desativarRevogaTokens() throws Exception {
        createOk();
        createOk();
        String ownerBearer = auth.ownerBearer();

        mockMvc.perform(withBearer(MockMvcRequestBuilders.post("/v1/users/" + userId + "/disable"), ownerBearer)).andExpect(status().isOk());

        assertThat(jdbc.queryForList("select revoked_reason from quick_entry_tokens where user_id = ?", String.class, userId))
                .containsExactly("USER_DISABLED", "USER_DISABLED");

        mockMvc.perform(withBearer(MockMvcRequestBuilders.post("/v1/users/" + userId + "/enable"), ownerBearer)).andExpect(status().isOk());

        mockMvc.perform(withBearer(MockMvcRequestBuilders.get("/v1/quick-entry-tokens"), bearer)).andExpect(status().isOk())
                .andExpect(jsonPath("$[0].status").value("REVOKED")).andExpect(jsonPath("$[1].status").value("REVOKED"))
                .andExpect(jsonPath("$[0].revokedReason").value("USER_DISABLED"));
    }
}
