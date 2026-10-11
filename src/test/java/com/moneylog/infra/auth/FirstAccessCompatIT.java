package com.moneylog.infra.auth;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.moneylog.core.auth.app.gateways.PasswordVerifier;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase;
import com.moneylog.core.auth.app.usecase.EnsureOwnerUseCase.EnsureOwnerCommand;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("Primeiro acesso: retrocompatibilidade com o hash do ambiente")
class FirstAccessCompatIT {

    private static final String EMAIL = "owner@example.com";
    private static final String ENV_PASSWORD = "senha-forte-123";
    private static final AtomicInteger IPS = new AtomicInteger(100);

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private PasswordVerifier verifier;
    @Autowired
    private org.springframework.transaction.PlatformTransactionManager transactionManager;
    @Autowired
    private EnsureOwnerUseCase ensureOwner;

    @AfterEach
    void restaurar() {
        committed(() -> jdbc.update("update users set password_hash = null, password_set_at = null, password_setup_pending = false,"
                + " setup_code_hash = null, setup_code_expires_at = null"));
    }

    private void committed(Runnable action) {
        new org.springframework.transaction.support.TransactionTemplate(transactionManager).executeWithoutResult(s -> action.run());
    }

    private MockHttpServletRequestBuilder login(String password) {
        return post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON).with(request -> {
            request.setRemoteAddr("198.51.100." + IPS.incrementAndGet());
            return request;
        }).content("{\"email\":\"" + EMAIL + "\",\"password\":\"" + password + "\"}");
    }

    @Test
    @DisplayName("com hash no ambiente e sem senha no banco: status false, login pelo ambiente vale e setup-password é recusado")
    void ambienteContinuaValendo() throws Exception {
        mockMvc.perform(get("/v1/auth/status")).andExpect(jsonPath("$.passwordSetupRequired").value(false));
        mockMvc.perform(login(ENV_PASSWORD)).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        mockMvc.perform(post("/v1/auth/setup-password").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + EMAIL + "\",\"setupCode\":\"qualquer-codigo\",\"newPassword\":\"uma-senha-sintetica-forte\"}"))
                .andExpect(status().isUnauthorized()).andExpect(jsonPath("$.code").value("INVALID_SETUP_CODE"));
    }

    @Test
    @DisplayName("senha do banco definida tem prioridade: a do ambiente deixa de valer")
    void bancoTemPrioridade() throws Exception {
        String hash = verifier.encode("senha-do-banco-sintetica");
        committed(() -> jdbc.update("update users set password_hash = ?", hash));

        mockMvc.perform(login("senha-do-banco-sintetica")).andExpect(status().isOk());
        mockMvc.perform(login(ENV_PASSWORD)).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/v1/auth/status")).andExpect(jsonPath("$.passwordSetupRequired").value(false));
    }

    @Test
    @DisplayName("redefinição pedida com hash no ambiente: o hash do ambiente deixa de entrar e o status vira true")
    void resetBloqueiaAmbiente() throws Exception {
        ensureOwner.handle(new EnsureOwnerCommand(EMAIL, true));

        mockMvc.perform(get("/v1/auth/status")).andExpect(jsonPath("$.passwordSetupRequired").value(true));
        mockMvc.perform(login(ENV_PASSWORD)).andExpect(status().isUnauthorized());
        assertThat(jdbc.queryForObject("select count(*) from users where email = ?", Integer.class, EMAIL)).isEqualTo(1);
    }
}
