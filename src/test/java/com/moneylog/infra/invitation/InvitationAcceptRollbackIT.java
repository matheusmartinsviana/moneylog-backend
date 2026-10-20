package com.moneylog.infra.invitation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doNothing;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import com.moneylog.core.auth.app.gateways.UserCreatedPublisher;
import com.moneylog.core.auth.app.gateways.UserGateway;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.MutableClockConfig;

@IntegrationTest
@Import(MutableClockConfig.class)
@TestPropertySource(properties = "moneylog.users.max=1000")
@DisplayName("Convites: aceite atômico com falha injetada")
class InvitationAcceptRollbackIT extends InvitationITSupport {

    @MockitoBean
    private UserCreatedPublisher publisher;
    @MockitoSpyBean
    private UserGateway spiedUsers;

    @AfterEach
    void resetDoubles() {
        reset(publisher, spiedUsers);
    }

    @Test
    @DisplayName("falha na semeadura (evento UserCreated): rollback total, convite PENDING e token ainda válido")
    void seedingFailureRollsBack() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String token = lastToken();
        doThrow(new IllegalStateException("falha injetada na semeadura")).when(publisher).publish(any());

        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
        assertThat(jdbc.queryForObject("select count(*) from refresh_tokens where user_id in (select id from users where email = ?)",
                Integer.class, email)).isZero();
        assertThat(dbStatus(id)).isEqualTo("PENDING");
        assertThat(jdbc.queryForObject("select accepted_at is null and accepted_user_id is null from user_invitations where id = ?",
                Boolean.class, id)).isTrue();

        doNothing().when(publisher).publish(any());
        mockMvc.perform(preview(token, newIp())).andExpect(status().isOk());
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isOk());
        assertThat(dbStatus(id)).isEqualTo("ACCEPTED");
    }

    @Test
    @DisplayName("falha no INSERT do usuário: rollback total, convite PENDING e token ainda válido")
    void userInsertFailureRollsBack() throws Exception {
        String email = newEmail();
        UUID id = inviteOk(email);
        String token = lastToken();
        doThrow(new IllegalStateException("falha injetada no insert")).when(spiedUsers).insertWithPassword(any(), any(), any());

        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, email)).isZero();
        assertThat(dbStatus(id)).isEqualTo("PENDING");

        reset(spiedUsers);
        mockMvc.perform(accept(token, NEW_PASSWORD, newIp())).andExpect(status().isOk());
        assertThat(dbStatus(id)).isEqualTo("ACCEPTED");
    }
}
