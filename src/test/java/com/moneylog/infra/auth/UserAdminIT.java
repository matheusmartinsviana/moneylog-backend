package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import jakarta.servlet.http.Cookie;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase;
import com.moneylog.core.auth.app.usecase.RefreshSessionUseCase.RefreshCommand;
import com.moneylog.core.auth.domain.User;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@DisplayName("Gestão de usuários: listar, desativar e reativar")
class UserAdminIT {

    private static final String ORIGIN = "https://app.moneylog.test";
    private static final String PASSWORD = "senha-do-membro-456";
    private static final AtomicInteger IPS = new AtomicInteger(10);
    private static final AtomicInteger SEQ = new AtomicInteger();

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private ObjectMapper objectMapper;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private UserGateway users;
    @Autowired
    private PasswordVerifier passwordVerifier;
    @Autowired
    private ItAuth auth;
    @Autowired
    private RefreshSessionUseCase refreshSession;
    @Autowired
    private PlatformTransactionManager transactionManager;

    private final List<UUID> created = new ArrayList<>();

    @AfterEach
    void limpar() {
        for (UUID id : created) {
            write("delete from refresh_tokens where user_id = ?", id);
            write("delete from users where id = ?", id);
        }
        created.clear();
    }

    private void write(String sql, Object... args) {
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> jdbc.update(sql, args));
    }

    private User member() {
        UUID id = UUID.randomUUID();
        String email = "adm-" + SEQ.incrementAndGet() + "-" + id.toString().substring(0, 8) + "@example.com";
        users.insert(User.member(id, email));
        created.add(id);
        write("update users set password_hash = ?, password_set_at = now() where id = ?", passwordVerifier.encode(PASSWORD), id);
        return users.findById(id).orElseThrow();
    }

    private MockHttpServletRequestBuilder login(String email) {
        return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr("198.51.100." + IPS.incrementAndGet() % 250);
            return request;
        }).content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}");
    }

    private MvcResult loginOk(String email) throws Exception {
        return mockMvc.perform(login(email)).andExpect(status().isOk()).andReturn();
    }

    private String bearer(MvcResult result) throws Exception {
        return "Bearer " + objectMapper.readTree(result.getResponse().getContentAsString()).get("accessToken").asText();
    }

    private static String refreshCookie(MvcResult result) {
        String setCookie = result.getResponse().getHeader("Set-Cookie");
        return setCookie.substring("ml_refresh=".length(), setCookie.indexOf(';'));
    }

    private MockHttpServletRequestBuilder refresh(String cookie) {
        return post("/v1/auth/refresh").header("Origin", ORIGIN).cookie(new Cookie("ml_refresh", cookie));
    }

    private MvcResult disable(UUID id) throws Exception {
        return mockMvc.perform(post("/v1/users/" + id + "/disable").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andReturn();
    }

    private int count(String table, UUID userId) {
        return jdbc.queryForObject("select count(*) from " + table + " where user_id = ?", Integer.class, userId);
    }

    @Test
    @DisplayName("dono lista usuários com o dono primeiro, sem dado financeiro, no contrato e com no-store")
    void lista() throws Exception {
        User member = member();
        loginOk(member.email());

        MvcResult result = mockMvc.perform(get("/v1/users").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "no-store"))
                .andExpect(jsonPath("$[0].role").value("OWNER")).andReturn();

        JsonNode body = objectMapper.readTree(result.getResponse().getContentAsString());
        JsonNode row = null;
        for (JsonNode node : body) {
            if (node.get("id").asText().equals(member.id().toString())) {
                row = node;
            }
        }
        assertThat(row).isNotNull();
        assertThat(row.get("role").asText()).isEqualTo("MEMBER");
        assertThat(row.get("status").asText()).isEqualTo("ACTIVE");
        assertThat(row.get("createdAt").asText()).isNotBlank();
        assertThat(row.get("lastLoginAt").asText()).isNotBlank();
        assertThat(row.propertyNames()).containsExactlyInAnyOrder("id", "email", "role", "status", "createdAt", "lastLoginAt");
    }

    @Test
    @DisplayName("desativar: DISABLED com disabled_at, sessões revogadas, 401 no access token, no login e no refresh, dados preservados; reativar volta a logar com a mesma senha")
    void desativaEReativa() throws Exception {
        User member = member();
        MvcResult session = loginOk(member.email());
        String token = bearer(session);
        String cookie = refreshCookie(session);
        mockMvc.perform(get("/v1/accounts").header("Authorization", token)).andExpect(status().isOk());
        int tokens = count("refresh_tokens", member.id());
        assertThat(tokens).isPositive();

        MvcResult disabled = disable(member.id());

        JsonNode body = objectMapper.readTree(disabled.getResponse().getContentAsString());
        assertThat(body.get("status").asText()).isEqualTo("DISABLED");
        assertThat(jdbc.queryForObject("select disabled_at is not null from users where id = ?", Boolean.class, member.id())).isTrue();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null", Integer.class,
                member.id())).isZero();
        assertThat(count("refresh_tokens", member.id())).isEqualTo(tokens);
        mockMvc.perform(get("/v1/accounts").header("Authorization", token)).andExpect(status().isUnauthorized());
        mockMvc.perform(refresh(cookie)).andExpect(status().isUnauthorized());
        mockMvc.perform(login(member.email())).andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_CREDENTIALS"));

        disable(member.id());

        mockMvc.perform(post("/v1/users/" + member.id() + "/enable").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isOk()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.status").value("ACTIVE"));
        assertThat(jdbc.queryForObject("select disabled_at is null from users where id = ?", Boolean.class, member.id())).isTrue();
        loginOk(member.email());
        mockMvc.perform(post("/v1/users/" + member.id() + "/enable").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isOk()).andExpect(jsonPath("$.status").value("ACTIVE"));
    }

    @Test
    @DisplayName("o dono não se desativa: 409 CANNOT_DISABLE_OWNER e nada muda")
    void donoNaoSeDesativa() throws Exception {
        mockMvc.perform(post("/v1/users/" + auth.ownerId() + "/disable").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isConflict()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("CANNOT_DISABLE_OWNER"));

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, auth.ownerId())).isEqualTo("ACTIVE");
        mockMvc.perform(get("/v1/users").header("Authorization", auth.ownerBearer())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("usuário inexistente: 404 USER_NOT_FOUND; id malformado: 400")
    void inexistente() throws Exception {
        mockMvc.perform(post("/v1/users/" + UUID.randomUUID() + "/disable").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isNotFound()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(post("/v1/users/" + UUID.randomUUID() + "/enable").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
        mockMvc.perform(post("/v1/users/xyz/disable").header("Authorization", auth.ownerBearer())).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("membro recebe 403 em listar, desativar e reativar; sem token recebe 401")
    void membroNaoGerencia() throws Exception {
        User member = member();
        User other = member();
        String token = bearer(loginOk(member.email()));

        mockMvc.perform(get("/v1/users").header("Authorization", token)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/users/" + other.id() + "/disable").header("Authorization", token)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/users/" + other.id() + "/enable").header("Authorization", token)).andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/users")).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, other.id())).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("a lista traz o dono primeiro e os membros por data de criação")
    void ordemDaLista() throws Exception {
        User third = member();
        User first = member();
        User second = member();
        write("update users set created_at = now() - interval '3 days' where id = ?", first.id());
        write("update users set created_at = now() - interval '2 days' where id = ?", second.id());
        write("update users set created_at = now() - interval '1 days' where id = ?", third.id());

        JsonNode body = objectMapper.readTree(mockMvc.perform(get("/v1/users").header("Authorization", auth.ownerBearer()))
                .andExpect(status().isOk()).andReturn().getResponse().getContentAsString());

        List<String> ids = new ArrayList<>();
        for (JsonNode node : body) {
            ids.add(node.get("id").asText());
        }
        assertThat(ids.get(0)).isEqualTo(auth.ownerId().toString());
        assertThat(ids.indexOf(first.id().toString())).isLessThan(ids.indexOf(second.id().toString()));
        assertThat(ids.indexOf(second.id().toString())).isLessThan(ids.indexOf(third.id().toString()));
    }

    @Test
    @DisplayName("desativar de novo preserva o disabled_at original")
    void disabledAtPreservado() throws Exception {
        User member = member();
        disable(member.id());
        java.sql.Timestamp original = jdbc.queryForObject("select disabled_at from users where id = ?", java.sql.Timestamp.class, member.id());

        disable(member.id());

        assertThat(jdbc.queryForObject("select disabled_at from users where id = ?", java.sql.Timestamp.class, member.id()))
                .isEqualTo(original);
    }

    @Test
    @DisplayName("desativar e reativar concorrentes no mesmo alvo nunca dão 409 e terminam consistentes")
    void concorrenciaNoMesmoAlvo() throws Exception {
        User member = member();
        String owner = auth.ownerBearer();
        int calls = 12;
        ExecutorService pool = Executors.newFixedThreadPool(calls);
        CountDownLatch start = new CountDownLatch(1);
        try {
            List<Future<Integer>> results = new ArrayList<>();
            for (int i = 0; i < calls; i++) {
                String action = i % 2 == 0 ? "disable" : "enable";
                results.add(pool.submit(() -> {
                    start.await();
                    return mockMvc.perform(post("/v1/users/" + member.id() + "/" + action).header("Authorization", owner))
                            .andReturn().getResponse().getStatus();
                }));
            }
            start.countDown();
            for (Future<Integer> result : results) {
                assertThat(result.get(30, TimeUnit.SECONDS)).isEqualTo(200);
            }
        } finally {
            pool.shutdownNow();
        }

        assertThat(jdbc.queryForObject("select (status = 'DISABLED') = (disabled_at is not null) from users where id = ?",
                Boolean.class, member.id())).isTrue();
    }

    @Test
    @DisplayName("rotação de refresh em andamento (FOR SHARE) e desativação: a desativação espera, e ao final não resta token ativo")
    void refreshContraDesativacao() throws Exception {
        User member = member();
        String cookie = refreshCookie(loginOk(member.email()));
        TransactionTemplate tx = new TransactionTemplate(transactionManager);
        CountDownLatch rotated = new CountDownLatch(1);
        CountDownLatch release = new CountDownLatch(1);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            Future<?> rotating = pool.submit(() -> tx.executeWithoutResult(status -> {
                refreshSession.handle(new RefreshCommand(cookie));
                rotated.countDown();
                try {
                    release.await(10, TimeUnit.SECONDS);
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                }
            }));
            assertThat(rotated.await(10, TimeUnit.SECONDS)).isTrue();
            Future<Integer> disabling = pool.submit(() -> mockMvc
                    .perform(post("/v1/users/" + member.id() + "/disable").header("Authorization", auth.ownerBearer()))
                    .andReturn().getResponse().getStatus());
            Thread.sleep(500);
            assertThat(disabling.isDone()).as("a desativação espera a rotação em andamento").isFalse();

            release.countDown();
            rotating.get(10, TimeUnit.SECONDS);

            assertThat(disabling.get(10, TimeUnit.SECONDS)).isEqualTo(200);
            assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id = ? and revoked_at is null",
                    Integer.class, member.id())).isZero();
            mockMvc.perform(refresh(cookie)).andExpect(status().isUnauthorized());
        } finally {
            release.countDown();
            pool.shutdownNow();
        }
    }
}
