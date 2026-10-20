package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import jakarta.servlet.http.Cookie;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.users.max=1000")
@DisplayName("QA T5/T6: ataques a convites e gestão de usuários")
class InvitationAttackQaIT extends InvitationITSupport {

    private ApplicationLogs logs;

    @BeforeEach
    void capture() {
        logs = ApplicationLogs.attach();
    }

    @AfterEach
    void release() {
        logs.detach();
    }

    private MvcResult acceptOk(String email) throws Exception {
        inviteOk(email);
        return mockMvc.perform(accept(lastToken(), NEW_PASSWORD, newIp())).andExpect(status().isOk()).andReturn();
    }

    private UUID userId(MvcResult accepted) throws Exception {
        return UUID.fromString(body(accepted).get("user").get("id").asText());
    }

    private String cookie(MvcResult accepted) {
        String header = accepted.getResponse().getHeader("Set-Cookie");
        return header.substring("ml_refresh=".length(), header.indexOf(';'));
    }

    private MvcResult refresh(String cookie) throws Exception {
        return mockMvc.perform(post("/v1/auth/refresh").header("Origin", "https://app.moneylog.test").cookie(new Cookie("ml_refresh", cookie)))
                .andReturn();
    }

    @Test
    @DisplayName("mass assignment: role/status no aceite e no convite não promovem ninguém")
    void massAssignmentIsHarmless() throws Exception {
        String email = newEmail();
        MvcResult created = mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\",\"role\":\"OWNER\",\"status\":\"ACTIVE\"}"))
                .andReturn();
        assertThat(created.getResponse().getStatus()).isIn(201, 400);
        if (created.getResponse().getStatus() == 400) {
            inviteOk(email);
        }
        String token = lastToken();
        int code = statusOf(json(post("/v1/invitations/accept").with(from(newIp())),
                "{\"token\":\"" + token + "\",\"password\":\"" + NEW_PASSWORD + "\",\"role\":\"OWNER\"}"));
        assertThat(code).isIn(200, 400);
        if (code == 400) {
            mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isOk());
        }
        assertThat(jdbc.queryForList("select role from users where lower(email) = lower(?)", String.class, email)).containsExactly("MEMBER");
        assertThat(jdbc.queryForObject("select count(*) from users where role = 'OWNER'", Integer.class)).isEqualTo(1);
    }

    @Test
    @DisplayName("X-Forwarded-For forjado não zera o rate limit do preview")
    void spoofedForwardedForDoesNotBypassRateLimit() throws Exception {
        String ip = newIp();
        int blocked = 0;
        for (int i = 0; i < 12; i++) {
            int code = statusOf(preview(SecureToken.generate(), ip).header("X-Forwarded-For", "203.0.113." + (i + 1)));
            if (code == 429) {
                blocked++;
            }
        }
        assertThat(blocked).isPositive();
    }

    @Test
    @DisplayName("e-mail do dono ou de usuário existente em outra caixa: 409 EMAIL_ALREADY_REGISTERED")
    void registeredEmailIsCaseInsensitive() throws Exception {
        String email = newEmail();
        existingUser(email, "ACTIVE");
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email.toUpperCase() + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + OWNER_EMAIL.toUpperCase() + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        assertThat(fake().sent()).isEmpty();
    }

    @Test
    @DisplayName("entradas hostis em preview/accept nunca dão 500")
    void hostileInputsNeverReturn500() throws Exception {
        List<String> bodies = List.of("", "null", "[]", "{}", "{\"token\":null}", "{\"token\":123}", "{\"token\":\"" + "a".repeat(20000) + "\"}",
                "{\"token\":\"\\u0000\\ud800\"}", "{\"token\":\"x\",\"password\":null}", "{\"token\":\"x\",\"password\":\"" + "p".repeat(100000) + "\"}",
                "{\"token\":\"' or 1=1 --\",\"password\":\"" + NEW_PASSWORD + "\"}");
        for (String path : List.of("/v1/invitations/preview", "/v1/invitations/accept")) {
            for (String body : bodies) {
                int code = statusOf(json(post(path).with(from(newIp())), body));
                assertThat(code).as("%s com %.40s", path, body).isBetween(400, 499);
            }
        }
        for (String path : List.of("/v1/invitations/preview", "/v1/invitations/accept")) {
            assertThat(statusOf(post(path).with(from(newIp())).contentType(MediaType.TEXT_PLAIN).content("x"))).isBetween(400, 499);
            assertThat(statusOf(get(path).with(from(newIp())))).isBetween(400, 499);
        }
    }

    @Test
    @DisplayName("listagem de convites e respostas de criação/reenvio/revogação não expõem token nem hash")
    void responsesNeverExposeToken() throws Exception {
        String email = newEmail();
        MvcResult created = invite(email);
        String token = lastToken();
        MvcResult resent = mockMvc.perform(ownerPost("/v1/invitations/" + body(created).get("id").asText() + "/resend", "")).andReturn();
        String newToken = lastToken();
        MvcResult listed = mockMvc.perform(ownerGet("/v1/invitations")).andExpect(status().isOk()).andReturn();
        MvcResult revoked = mockMvc.perform(ownerDelete("/v1/invitations/" + body(created).get("id").asText())).andReturn();
        for (MvcResult result : List.of(created, resent, listed, revoked)) {
            String content = result.getResponse().getContentAsString();
            for (String secret : List.of(token, newToken, SecureToken.hash(token), SecureToken.hash(newToken))) {
                assertThat(content).doesNotContain(secret);
            }
            assertThat(content.toLowerCase()).doesNotContain("tokenhash").doesNotContain("token_hash");
            assertThat(String.valueOf(result.getResponse().getHeader("Location"))).doesNotContain(token).doesNotContain(newToken);
        }
        assertThat(logs.messages()).noneMatch(m -> m.contains(token) || m.contains(newToken) || m.contains(SecureToken.hash(token)));
    }

    @Test
    @DisplayName("falha de envio, senha fraca, token inválido e rate limit não vazam token, hash ou senha nos logs")
    void failurePathsDoNotLogSecrets() throws Exception {
        fake().failNextSends(true);
        MvcResult failed = invite(newEmail());
        fake().failNextSends(false);
        assertThat(failed.getResponse().getStatus()).isEqualTo(502);
        UUID id = UUID.fromString(body(failed).get("invitationId").asText());
        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isOk());
        String token = lastToken();
        mockMvc.perform(accept(token, "curta", newIp())).andExpect(status().isUnprocessableEntity());
        String bad = SecureToken.generate();
        String ip = newIp();
        for (int i = 0; i < 7; i++) {
            mockMvc.perform(accept(bad, "senha-vazada-123456", ip));
        }
        assertThat(logs.messages()).noneMatch(m -> m.contains(token) || m.contains(SecureToken.hash(token)) || m.contains(bad)
                || m.contains(SecureToken.hash(bad)) || m.contains("senha-vazada-123456") || m.contains("curta"));
    }

    @Test
    @DisplayName("reenvio mata o token anterior; revogar e convidar de novo emite token novo e o revogado continua morto")
    void resendAndReinviteKillOldTokens() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String first = lastToken();
        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isOk());
        String second = lastToken();
        assertThat(second).isNotEqualTo(first);
        mockMvc.perform(preview(first, newIp())).andExpect(status().isNotFound());
        mockMvc.perform(accept(first, NEW_PASSWORD, newIp())).andExpect(status().isNotFound());
        mockMvc.perform(ownerDelete("/v1/invitations/" + id)).andExpect(status().isOk());
        mockMvc.perform(accept(second, NEW_PASSWORD, newIp())).andExpect(status().isNotFound());
        inviteOk(email);
        String third = lastToken();
        mockMvc.perform(accept(second, NEW_PASSWORD, newIp())).andExpect(status().isNotFound());
        mockMvc.perform(accept(third, NEW_PASSWORD, newIp())).andExpect(status().isOk());
        assertThat(jdbc.queryForObject("select count(*) from users where lower(email) = lower(?)", Integer.class, email)).isEqualTo(1);
    }

    @Test
    @DisplayName("membro vindo do aceite: 403 na gestão, nunca vê dados do dono e não afeta o dono")
    void acceptedMemberIsIsolated() throws Exception {
        MvcResult accepted = acceptOk(newEmail());
        String bearer = "Bearer " + body(accepted).get("accessToken").asText();
        UUID ownerId = auth.ownerId();
        for (String path : List.of("/v1/users", "/v1/invitations")) {
            mockMvc.perform(get(path).header("Authorization", bearer)).andExpect(status().isForbidden());
        }
        mockMvc.perform(post("/v1/users/" + ownerId + "/disable").header("Authorization", bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/users/" + userId(accepted) + "/disable").header("Authorization", bearer)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/invitations").header("Authorization", bearer).contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"" + newEmail() + "\"}")).andExpect(status().isForbidden());
        MvcResult accounts = mockMvc.perform(get("/v1/accounts").header("Authorization", bearer)).andExpect(status().isOk()).andReturn();
        String ownerAccountIds = String.join(",", jdbc.queryForList("select id::text from accounts where user_id = ?", String.class, ownerId));
        for (String ownerAccount : ownerAccountIds.split(",")) {
            if (!ownerAccount.isBlank()) {
                assertThat(accounts.getResponse().getContentAsString()).doesNotContain(ownerAccount);
                mockMvc.perform(get("/v1/accounts/" + ownerAccount).header("Authorization", bearer)).andExpect(status().isNotFound());
            }
        }
        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, ownerId)).isEqualTo("ACTIVE");
    }

    @Test
    @DisplayName("convite → aceite → desativar → access/refresh/login 401 → reativar: sessão antiga segue morta e login novo funciona")
    void fullLifecycleWithDisable() throws Exception {
        String email = newEmail();
        MvcResult accepted = acceptOk(email);
        UUID id = userId(accepted);
        String bearer = "Bearer " + body(accepted).get("accessToken").asText();
        String oldCookie = cookie(accepted);
        mockMvc.perform(get("/v1/accounts").header("Authorization", bearer)).andExpect(status().isOk());
        assertThat(refresh(oldCookie).getResponse().getStatus()).isEqualTo(200);

        for (int i = 0; i < 3; i++) {
            mockMvc.perform(post("/v1/users/" + id + "/disable").header("Authorization", auth.ownerBearer())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("DISABLED"));
        }
        mockMvc.perform(get("/v1/accounts").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/users").header("Authorization", bearer)).andExpect(status().isUnauthorized());
        assertThat(refresh(oldCookie).getResponse().getStatus()).isEqualTo(401);
        MvcResult denied = mockMvc.perform(login(email, NEW_PASSWORD)).andExpect(status().isUnauthorized()).andReturn();
        MvcResult wrong = mockMvc.perform(login(email, "senha-errada-000000")).andExpect(status().isUnauthorized()).andReturn();
        assertThat(body(denied).get("code").asText()).isEqualTo(body(wrong).get("code").asText());
        assertThat(body(denied).get("detail").asText()).isEqualTo(body(wrong).get("detail").asText());

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(post("/v1/users/" + id + "/enable").header("Authorization", auth.ownerBearer())).andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("ACTIVE"));
        }
        assertThat(refresh(oldCookie).getResponse().getStatus()).isEqualTo(401);
        mockMvc.perform(get("/v1/accounts").header("Authorization", bearer)).andExpect(status().isOk());
        mockMvc.perform(login(email, NEW_PASSWORD)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("usuário desativado continua ocupando vaga do teto e seu e-mail não pode ser convidado")
    void disabledUserKeepsSlotAndEmail() throws Exception {
        String email = newEmail();
        UUID id = userId(acceptOk(email));
        mockMvc.perform(post("/v1/users/" + id + "/disable").header("Authorization", auth.ownerBearer())).andExpect(status().isOk());
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED"));
        assertThat(jdbc.queryForObject("select count(*) from users where id = ?", Integer.class, id)).isEqualTo(1);
    }

    @Test
    @DisplayName("tokens de desativado/ id de inexistente em enable/disable: 404 sem 500; MEMBER sem sessão: 401")
    void adminEdgeCases() throws Exception {
        UUID ghost = UUID.randomUUID();
        for (String action : List.of("disable", "enable")) {
            mockMvc.perform(post("/v1/users/" + ghost + "/" + action).header("Authorization", auth.ownerBearer())).andExpect(status().isNotFound())
                    .andExpect(jsonPath("$.code").value("USER_NOT_FOUND"));
            mockMvc.perform(post("/v1/users/" + ghost + "/" + action)).andExpect(status().isUnauthorized());
            mockMvc.perform(post("/v1/users/" + ghost + "/" + action).header("Authorization", "Bearer lixo")).andExpect(status().isUnauthorized());
            mockMvc.perform(get("/v1/users/" + ghost + "/" + action).header("Authorization", auth.ownerBearer()))
                    .andExpect(status().is4xxClientError());
        }
        mockMvc.perform(post("/v1/users/" + auth.ownerId() + "/enable").header("Authorization", auth.ownerBearer())).andExpect(status().isOk());
    }
}
