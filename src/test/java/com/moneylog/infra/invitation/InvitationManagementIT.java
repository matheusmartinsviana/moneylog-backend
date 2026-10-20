package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase.PurgeInvitationsCommand;
import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.users.max=1000")
@DisplayName("Convites: gestão pelo dono")
class InvitationManagementIT extends InvitationITSupport {

    @Autowired
    private PurgeInvitationsUseCase purge;

    @Test
    @DisplayName("convidar e-mail novo: 201 + Location, PENDING por 7 dias, 1 e-mail com /convite#token, banco só com o hash")
    void createsInvitation() throws Exception {
        String email = newEmail();

        MvcResult result = mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email.toUpperCase() + "\"}"))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(jsonPath("$.deliveryStatus").value("SENT"))
                .andExpect(jsonPath("$.email").value(email)).andReturn();

        JsonNode created = body(result);
        UUID id = UUID.fromString(created.get("id").asText());
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/invitations/" + id);
        assertThat(Instant.parse(created.get("expiresAt").asText())).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        assertThat(Instant.parse(created.get("lastSentAt").asText())).isEqualTo(clock.instant());
        assertThat(fake().sent()).hasSize(1);
        assertThat(fake().last().to()).isEqualTo(email);
        String token = lastToken();
        assertThat(token).hasSize(43);
        assertThat(result.getResponse().getContentAsString()).doesNotContain(token);
        assertThat(jdbc.queryForObject("select token_hash from user_invitations where id = ?", String.class, id))
                .isEqualTo(SecureToken.hash(token)).isNotEqualTo(token);
        assertThat(jdbc.queryForObject("select count(*) from user_invitations where token_hash = ? or email = ?", Integer.class, token, token))
                .isZero();
        assertThat(fake().last().html()).contains("/convite#" + token);
    }

    @Test
    @DisplayName("e-mail inválido ou campo desconhecido: 400 VALIDATION_FAILED, sem envio")
    void validatesInput() throws Exception {
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"nao-e-email\"}")).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_FAILED"));
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"\"}")).andExpect(status().isBadRequest());
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"a@b.com\",\"role\":\"OWNER\"}")).andExpect(status().isBadRequest());
        assertThat(fake().sent()).isEmpty();
    }

    @Test
    @DisplayName("e-mail que já é usuário (qualquer caixa, ativo ou desativado, até o dono): 409 EMAIL_ALREADY_REGISTERED sem envio")
    void refusesRegisteredEmails() throws Exception {
        String active = newEmail();
        String disabled = newEmail();
        existingUser(active, "ACTIVE");
        existingUser(disabled, "DISABLED");

        for (String email : List.of(active.toUpperCase(), disabled, OWNER_EMAIL.toUpperCase())) {
            mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED")).andExpect(ContractValidation.conforms());
        }
        assertThat(fake().sent()).isEmpty();
        assertThat(jdbc.queryForObject("select count(*) from user_invitations", Integer.class)).isZero();
    }

    @Test
    @DisplayName("PENDING não expirado: 409 INVITATION_PENDING com o id existente; PENDING vencido: o anterior vira EXPIRED e o novo nasce")
    void handlesPendingAndOverduePending() throws Exception {
        String email = newEmail();
        UUID first = inviteOk(email);

        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_PENDING")).andExpect(jsonPath("$.invitationId").value(first.toString()))
                .andExpect(ContractValidation.conforms());
        assertThat(fake().sent()).hasSize(1);

        advance(Duration.ofDays(7).plusSeconds(1));
        UUID second = inviteOk(email);

        assertThat(second).isNotEqualTo(first);
        assertThat(dbStatus(first)).isEqualTo("EXPIRED");
        assertThat(dbStatus(second)).isEqualTo("PENDING");
        assertThat(fake().sent()).hasSize(2);
    }

    @Test
    @DisplayName("falha no envio: convite salvo FAILED, 502 com invitationId, resend o entrega e o token novo funciona")
    void failedDeliveryCanBeResent() throws Exception {
        String email = newEmail();
        fake().failNextSends(true);

        MvcResult failed = mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}"))
                .andExpect(status().isBadGateway()).andExpect(jsonPath("$.code").value("EMAIL_DELIVERY_FAILED"))
                .andExpect(ContractValidation.conforms()).andReturn();

        UUID id = UUID.fromString(body(failed).get("invitationId").asText());
        assertThat(failed.getResponse().getContentAsString()).doesNotContain("sem rede").doesNotContain("Falha simulada");
        assertThat(dbStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select delivery_status from user_invitations where id = ?", String.class, id)).isEqualTo("FAILED");
        assertThat(fake().sent()).isEmpty();

        fake().failNextSends(false);
        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.deliveryStatus").value("SENT")).andExpect(ContractValidation.conforms());
        assertThat(fake().sent()).hasSize(1);
        mockMvc.perform(preview(lastToken(), newIp())).andExpect(status().isOk());
        assertThat(storedStatuses(email)).containsExactly("PENDING");
    }

    @Test
    @DisplayName("reenviar PENDING: novo token e nova validade; o token antigo dá 404 e o novo funciona")
    void resendsPending() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String oldToken = lastToken();
        advance(Duration.ofDays(2));

        MvcResult resent = mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(header().string("Cache-Control", "private, no-store"))
                .andExpect(jsonPath("$.status").value("PENDING")).andReturn();

        String newToken = lastToken();
        assertThat(newToken).isNotEqualTo(oldToken);
        assertThat(Instant.parse(body(resent).get("expiresAt").asText())).isEqualTo(clock.instant().plus(Duration.ofDays(7)));
        mockMvc.perform(preview(oldToken, newIp())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        mockMvc.perform(preview(newToken, newIp())).andExpect(status().isOk());
        assertThat(fake().sent()).hasSize(2);
    }

    @Test
    @DisplayName("reenviar EXPIRED sem conflito volta a PENDING com token novo")
    void resendsExpired() throws Exception {
        UUID id = inviteOk(newEmail());
        String oldToken = lastToken();
        advance(Duration.ofDays(8));

        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PENDING")).andExpect(ContractValidation.conforms());

        assertThat(dbStatus(id)).isEqualTo("PENDING");
        mockMvc.perform(preview(oldToken, newIp())).andExpect(status().isNotFound());
        mockMvc.perform(preview(lastToken(), newIp())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("reenviar EXPIRED com outro PENDING do mesmo e-mail: 409 INVITATION_PENDING com o id do pendente, sem 500")
    void resendExpiredWithOtherPending() throws Exception {
        String email = newEmail();
        UUID expired = inviteOk(email);
        advance(Duration.ofDays(8));
        UUID pending = inviteOk(email);

        mockMvc.perform(ownerPost("/v1/invitations/" + expired + "/resend", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_PENDING")).andExpect(jsonPath("$.invitationId").value(pending.toString()))
                .andExpect(ContractValidation.conforms());

        assertThat(dbStatus(expired)).isEqualTo("EXPIRED");
        assertThat(dbStatus(pending)).isEqualTo("PENDING");
        assertThat(fake().sent()).hasSize(2);
    }

    @Test
    @DisplayName("reenviar EXPIRED cujo e-mail virou usuário: 409 EMAIL_ALREADY_REGISTERED sem envio")
    void resendExpiredWithRegisteredEmail() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        advance(Duration.ofDays(8));
        existingUser(email, "ACTIVE");

        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("EMAIL_ALREADY_REGISTERED")).andExpect(ContractValidation.conforms());

        assertThat(fake().sent()).hasSize(1);
        assertListedAs(id, "EXPIRED");
    }

    @Test
    @DisplayName("reenviar REVOKED ou ACCEPTED: 409 INVITATION_NOT_RESENDABLE; id desconhecido: 404 INVITATION_NOT_FOUND")
    void resendRefusals() throws Exception {
        UUID revoked = inviteOk(newEmail());
        mockMvc.perform(ownerDelete("/v1/invitations/" + revoked)).andExpect(status().isOk());
        UUID accepted = inviteOk(newEmail());
        mockMvc.perform(accept(lastToken(), NEW_PASSWORD, newIp())).andExpect(status().isOk());

        for (UUID id : List.of(revoked, accepted)) {
            mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("INVITATION_NOT_RESENDABLE")).andExpect(ContractValidation.conforms());
        }
        mockMvc.perform(ownerPost("/v1/invitations/" + UUID.randomUUID() + "/resend", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND")).andExpect(ContractValidation.conforms());
        assertThat(dbStatus(accepted)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("revogar PENDING e EXPIRED vira REVOKED e repetir é idempotente; ACCEPTED dá 409; o token revogado dá 404")
    void revokes() throws Exception {
        UUID pending = inviteOk(newEmail());
        String token = lastToken();
        mockMvc.perform(ownerDelete("/v1/invitations/" + pending)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"))
                .andExpect(ContractValidation.conforms());
        mockMvc.perform(ownerDelete("/v1/invitations/" + pending)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
        mockMvc.perform(preview(token, newIp())).andExpect(status().isNotFound());
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isNotFound());

        UUID expired = inviteOk(newEmail());
        advance(Duration.ofDays(8));
        mockMvc.perform(ownerDelete("/v1/invitations/" + expired)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));

        UUID accepted = inviteOk(newEmail());
        mockMvc.perform(accept(lastToken(), NEW_PASSWORD, newIp())).andExpect(status().isOk());
        mockMvc.perform(ownerDelete("/v1/invitations/" + accepted)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_REVOCABLE")).andExpect(ContractValidation.conforms());
        mockMvc.perform(ownerDelete("/v1/invitations/" + UUID.randomUUID())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATION_NOT_FOUND"));
    }

    @Test
    @DisplayName("listar: mais novos primeiro, PENDING vencido sai como EXPIRED, filtro por status, sem token")
    void lists() throws Exception {
        UUID first = inviteOk(newEmail());
        String firstToken = lastToken();
        advance(Duration.ofDays(8));
        UUID second = inviteOk(newEmail());
        String secondToken = lastToken();

        MvcResult all = mockMvc.perform(ownerGet("/v1/invitations")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(header().string("Cache-Control", "private, no-store")).andReturn();
        JsonNode list = body(all);
        assertThat(list).hasSize(2);
        assertThat(list.get(0).get("id").asText()).isEqualTo(second.toString());
        assertThat(list.get(0).get("status").asText()).isEqualTo("PENDING");
        assertThat(list.get(1).get("id").asText()).isEqualTo(first.toString());
        assertThat(list.get(1).get("status").asText()).isEqualTo("EXPIRED");
        assertThat(all.getResponse().getContentAsString()).doesNotContain(firstToken).doesNotContain(secondToken);

        mockMvc.perform(ownerGet("/v1/invitations?status=EXPIRED")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].id").value(first.toString()));
        mockMvc.perform(ownerGet("/v1/invitations?status=ACCEPTED")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(0));
        mockMvc.perform(ownerGet("/v1/invitations?status=QUALQUER")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("MEMBER recebe 403 em todas as rotas autenticadas de convite; sem token, 401")
    void memberIsForbidden() throws Exception {
        String memberToken = auth.bearer(existingUser(newEmail(), "ACTIVE").id());
        UUID id = UUID.randomUUID();

        mockMvc.perform(post("/v1/invitations").header("Authorization", memberToken).contentType("application/json").content("{\"email\":\"a@b.com\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/v1/invitations").header("Authorization", memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(post("/v1/invitations/" + id + "/resend").header("Authorization", memberToken)).andExpect(status().isForbidden());
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete("/v1/invitations/" + id)
                .header("Authorization", memberToken)).andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
        mockMvc.perform(get("/v1/invitations")).andExpect(status().isUnauthorized());
        assertThat(fake().sent()).isEmpty();
    }

    @Test
    @DisplayName("retenção: apaga só finais antigos (ACCEPTED, REVOKED, EXPIRED) e persiste vencidos; nunca um PENDING")
    void retentionPurgesOnlyOldFinals() throws Exception {
        Instant now = clock.instant();
        Instant old = now.minus(Duration.ofDays(100));
        Instant recent = now.minus(Duration.ofDays(10));
        UUID ownerId = auth.ownerId();
        UUID acceptedUser = existingUser(newEmail(), "ACTIVE").id();
        UUID oldAccepted = row(ownerId, "ACCEPTED", old, old.plusSeconds(10), old.plusSeconds(5), acceptedUser);
        UUID oldRevoked = row(ownerId, "REVOKED", old, old.plusSeconds(10), null, null);
        UUID oldExpired = row(ownerId, "EXPIRED", old, old.plusSeconds(10), null, null);
        UUID oldOverduePending = row(ownerId, "PENDING", old, old.plusSeconds(10), null, null);
        UUID recentRevoked = row(ownerId, "REVOKED", recent, recent.plusSeconds(10), null, null);
        UUID recentExpired = row(ownerId, "EXPIRED", recent.minus(Duration.ofDays(8)), recent, null, null);
        UUID livePending = row(ownerId, "PENDING", now, now.plus(Duration.ofDays(7)), null, null);

        var result = purge.handle(new PurgeInvitationsCommand(Duration.ofDays(90)));

        assertThat(result.expired()).isEqualTo(1);
        assertThat(result.deleted()).isEqualTo(4);
        List<UUID> remaining = jdbc.queryForList("select id from user_invitations", UUID.class);
        assertThat(remaining).containsExactlyInAnyOrder(recentRevoked, recentExpired, livePending);
        assertThat(remaining).doesNotContain(oldAccepted, oldRevoked, oldExpired, oldOverduePending);
        assertThat(dbStatus(livePending)).isEqualTo("PENDING");
    }

    private void assertListedAs(UUID id, String expected) throws Exception {
        JsonNode list = body(mockMvc.perform(ownerGet("/v1/invitations")).andExpect(status().isOk()).andReturn());
        for (JsonNode item : list) {
            if (item.get("id").asText().equals(id.toString())) {
                assertThat(item.get("status").asText()).isEqualTo(expected);
                return;
            }
        }
        throw new AssertionError("convite não listado: " + id);
    }

    private UUID row(UUID invitedBy, String status, Instant createdAt, Instant expiresAt, Instant acceptedAt, UUID acceptedUser) {
        UUID id = UUID.randomUUID();
        Instant updatedAt = "REVOKED".equals(status) ? expiresAt : createdAt;
        write("insert into user_invitations (id, email, token_hash, status, invited_by, created_at, updated_at, expires_at, last_sent_at, delivery_status, accepted_at, accepted_user_id) "
                        + "values (?, ?, ?, ?, ?, ?, ?, ?, ?, 'SENT', ?, ?)", id, newEmail(), SecureToken.hash(id.toString()), status, invitedBy,
                java.sql.Timestamp.from(createdAt), java.sql.Timestamp.from(updatedAt), java.sql.Timestamp.from(expiresAt),
                java.sql.Timestamp.from(createdAt), acceptedAt == null ? null : java.sql.Timestamp.from(acceptedAt), acceptedUser);
        return id;
    }
}
