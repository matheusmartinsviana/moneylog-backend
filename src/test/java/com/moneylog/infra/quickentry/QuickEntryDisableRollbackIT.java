package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.HttpHeaders;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.SqlFixtures;

@IntegrationTest
@DisplayName("Desativar usuário: falha na revogação dos tokens desfaz a desativação")
class QuickEntryDisableRollbackIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager transactionManager;
    @MockitoBean
    private QuickEntryTokenGateway tokens;

    @Test
    @DisplayName("se revokeAllActive falha, o usuário continua ACTIVE e a resposta é 500 sem vazar a causa")
    void deveDesfazerADesativacao() throws Exception {
        UUID userId = UUID.randomUUID();
        new TransactionTemplate(transactionManager).executeWithoutResult(status -> SqlFixtures.insertUser(jdbc, userId, "rb-" + userId + "@example.com"));
        doThrow(new IllegalStateException("falha simulada")).when(tokens).revokeAllActive(any(), any(), any());

        mockMvc.perform(MockMvcRequestBuilders.post("/v1/users/" + userId + "/disable").header(HttpHeaders.AUTHORIZATION, auth.ownerBearer()))
                .andExpect(status().isInternalServerError());

        assertThat(jdbc.queryForObject("select status from users where id = ?", String.class, userId)).isEqualTo("ACTIVE");
    }
}
