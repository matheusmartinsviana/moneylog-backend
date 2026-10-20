package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.moneylog.core.sk.id.SecureToken;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = {"moneylog.invitations.enabled=false", "moneylog.users.max=1000"})
@DisplayName("Convites: flag moneylog.invitations.enabled desligada")
class InvitationDisabledIT extends InvitationITSupport {

    private UUID pendingRow(String token) {
        UUID id = UUID.randomUUID();
        Instant now = clock.instant();
        write("insert into user_invitations (id, email, token_hash, status, invited_by, created_at, updated_at, expires_at, last_sent_at, delivery_status) "
                        + "values (?, ?, ?, 'PENDING', ?, ?, ?, ?, ?, 'SENT')", id, newEmail(), SecureToken.hash(token), auth.ownerId(),
                java.sql.Timestamp.from(now), java.sql.Timestamp.from(now), java.sql.Timestamp.from(now.plusSeconds(3600)),
                java.sql.Timestamp.from(now));
        return id;
    }

    @Test
    @DisplayName("convidar e reenviar respondem 404 INVITATIONS_DISABLED, sem envio")
    void createAndResendAreNotFound() throws Exception {
        UUID id = pendingRow("qualquer-token-de-teste-0123456789");

        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + newEmail() + "\"}")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATIONS_DISABLED")).andExpect(ContractValidation.conforms());
        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVITATIONS_DISABLED")).andExpect(ContractValidation.conforms());
        assertThat(fake().sent()).isEmpty();
    }

    @Test
    @DisplayName("preview e aceite respondem 404 INVALID_INVITATION mesmo com token válido, e nada é criado")
    void previewAndAcceptAreInvalid() throws Exception {
        String token = SecureToken.generate();
        UUID id = pendingRow(token);

        mockMvc.perform(preview(token, newIp())).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("INVALID_INVITATION"));
        assertThat(dbStatus(id)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("listar e revogar continuam funcionando, e o MEMBER segue com 403")
    void listAndRevokeStillWork() throws Exception {
        UUID id = pendingRow(SecureToken.generate());

        mockMvc.perform(ownerGet("/v1/invitations")).andExpect(status().isOk()).andExpect(jsonPath("$.length()").value(1));
        mockMvc.perform(ownerDelete("/v1/invitations/" + id)).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("REVOKED"));
    }
}
