package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.users.max=4")
@DisplayName("Convites: teto moneylog.users.max")
class InvitationUserLimitIT extends InvitationITSupport {

    @Test
    @DisplayName("usuários (ativos e desativados) + pendentes não expirados bloqueiam; REVOKED e EXPIRED não contam")
    void enforcesUserLimit() throws Exception {
        existingUser(newEmail(), "DISABLED");
        UUID slot = inviteOk(newEmail());
        UUID last = inviteOk(newEmail());

        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + newEmail() + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LIMIT_REACHED")).andExpect(ContractValidation.conforms());
        assertThat(fake().sent()).hasSize(2);

        mockMvc.perform(ownerDelete("/v1/invitations/" + slot)).andExpect(status().isOk());
        inviteOk(newEmail());
        mockMvc.perform(ownerPost("/v1/invitations", "{\"email\":\"" + newEmail() + "\"}")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LIMIT_REACHED"));

        advance(Duration.ofDays(8));
        inviteOk(newEmail());
        assertThat(dbStatus(last)).isEqualTo("PENDING");
    }

    @Test
    @DisplayName("reenviar EXPIRED com o teto atingido: 409 USER_LIMIT_REACHED, sem envio, e o convite continua EXPIRED")
    void resendExpiredAtLimit() throws Exception {
        UUID id = inviteOk(newEmail());
        advance(Duration.ofDays(8));
        existingUser(newEmail(), "ACTIVE");
        existingUser(newEmail(), "ACTIVE");
        existingUser(newEmail(), "ACTIVE");

        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("USER_LIMIT_REACHED")).andExpect(ContractValidation.conforms());

        mockMvc.perform(ownerGet("/v1/invitations")).andExpect(status().isOk()).andExpect(jsonPath("$[0].id").value(id.toString()))
                .andExpect(jsonPath("$[0].status").value("EXPIRED"));
        assertThat(fake().sent()).hasSize(1);
    }

    @Test
    @DisplayName("reenviar PENDING no teto é permitido: o convite já ocupa a própria vaga")
    void resendPendingAtLimit() throws Exception {
        UUID id = inviteOk(newEmail());
        existingUser(newEmail(), "ACTIVE");
        existingUser(newEmail(), "ACTIVE");

        mockMvc.perform(ownerPost("/v1/invitations/" + id + "/resend", "")).andExpect(status().isOk());
        assertThat(fake().sent()).hasSize(2);
    }

    @Test
    @DisplayName("convites simultâneos nunca passam do teto")
    void concurrentInvitesNeverExceedLimit() throws Exception {
        existingUser(newEmail(), "DISABLED");
        java.util.List<java.util.concurrent.Callable<Integer>> tasks = new java.util.ArrayList<>();
        for (int i = 0; i < 5; i++) {
            String email = newEmail();
            tasks.add(() -> statusOf(ownerPost("/v1/invitations", "{\"email\":\"" + email + "\"}")));
        }

        java.util.List<Integer> statuses = runTogether(tasks);

        assertThat(statuses.stream().filter(s -> s == 201)).hasSize(2);
        assertThat(statuses.stream().filter(s -> s == 409)).hasSize(3);
        assertThat(jdbc.queryForObject("select count(*) from user_invitations where status = 'PENDING'", Integer.class)).isEqualTo(2);
        assertThat(fake().sent()).hasSize(2);
    }
}
