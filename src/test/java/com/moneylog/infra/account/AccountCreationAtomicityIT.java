package com.moneylog.infra.account;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;

import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@DisplayName("Criação de conta é atômica (T25 CA1)")
class AccountCreationAtomicityIT {

    @Autowired
    private MockMvc mockMvc;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @MockitoSpyBean
    private LedgerWriteGateway ledger;

    @Test
    @DisplayName("falha ao gravar o lançamento do saldo inicial: 500 sem vazar a causa e nenhuma conta é gravada")
    void naoDeveGravarContaSemOLancamento() throws Exception {
        doThrow(new IllegalStateException("falha simulada no razão")).when(ledger).append(any(), any());

        mockMvc.perform(post("/v1/accounts").header("Authorization", auth.ownerBearer()).contentType(MediaType.APPLICATION_JSON)
                        .content("{\"name\":\"Atômica\",\"type\":\"CHECKING\",\"openingBalance\":{\"amount\":\"10.00\",\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}"))
                .andExpect(status().isInternalServerError()).andExpect(jsonPath("$.code").value("INTERNAL_ERROR"))
                .andExpect(org.springframework.test.web.servlet.result.MockMvcResultMatchers.content().string(
                        org.hamcrest.Matchers.not(org.hamcrest.Matchers.containsString("falha simulada"))));

        assertThat(jdbc.queryForObject("select count(*) from accounts where name = 'Atômica'", Integer.class)).isZero();
    }
}
