package com.moneylog.infra.export;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;

/**
 * M1 da revisão da Etapa 5 (S-260): teto de volume (422 {@code EXPORT_TOO_LARGE}) e limite de taxa (429 {@code TOO_MANY_ATTEMPTS}) das rotas de exportação.
 * Os limites reais são 200.000 movimentações e 6 pedidos por minuto por usuário; aqui são reduzidos por propriedade para o teste ser rápido.
 */
@TestPropertySource(properties = {"moneylog.export.max-transactions=5", "moneylog.export.rate-limit.max-requests=4", "moneylog.export.rate-limit.window=PT1M"})
@DisplayName("Exportação: teto de volume e limite de taxa (M1, S-260)")
class ExportLimitsIT extends E4ItBase {

    private void expenses(int count) throws Exception {
        for (int i = 0; i < count; i++) {
            created("{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"date\":\"2026-10-10\",\"description\":\"Despesa " + i + "\",\"accountId\":\"" + nubank
                    + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");
        }
    }

    @Test
    @DisplayName("acima do teto: o backup e o CSV respondem 422 EXPORT_TOO_LARGE (problem+json, sem byte do arquivo); no teto ou abaixo, 200; o CSV filtrado para baixo do teto passa")
    void deveRecusarAcimaDoTeto() throws Exception {
        expenses(5);
        mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        mockMvc.perform(get("/v1/exports/transactions.csv")).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
        advanceTo(Instant.parse("2026-10-10T15:00:00Z").plusSeconds(120)); // nova janela de taxa
        created("{\"type\":\"EXPENSE\",\"amount\":\"10.00\",\"date\":\"2026-10-11\",\"description\":\"A sexta\",\"accountId\":\"" + nubank
                + "\",\"categoryId\":\"" + food + "\",\"method\":\"PIX\"}");

        MvcResult backup = mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE")).andReturn();
        assertThat(backup.getResponse().getContentType()).contains("problem+json");
        assertThat(backup.getResponse().getContentAsString()).doesNotContain("schemaVersion");
        MvcResult csv = mockMvc.perform(get("/v1/exports/transactions.csv")).andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("EXPORT_TOO_LARGE")).andReturn();
        assertThat(csv.getResponse().getContentAsString()).doesNotContain("Data;Tipo");
        mockMvc.perform(get("/v1/exports/transactions.csv?from=2026-10-11&to=2026-10-11")).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("limite de taxa: o 5º pedido na janela é 429 TOO_MANY_ATTEMPTS com Retry-After (CSV e backup dividem o limite); o login e as demais rotas não são afetados; a janela libera")
    void deveLimitarATaxa() throws Exception {
        for (int i = 0; i < 2; i++) {
            mockMvc.perform(get("/v1/exports/transactions.csv")).andExpect(status().isOk());
            mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk());
        }

        MvcResult limited = mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isTooManyRequests()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("TOO_MANY_ATTEMPTS")).andReturn();
        assertThat(Long.parseLong(limited.getResponse().getHeader(HttpHeaders.RETRY_AFTER))).isBetween(1L, 60L);
        mockMvc.perform(get("/v1/exports/transactions.csv")).andExpect(status().isTooManyRequests());
        mockMvc.perform(get("/v1/dashboard/summary")).andExpect(status().isOk());
        String other = otherBearer();
        mockMvc.perform(getAs(other, "/v1/exports/backup")).andExpect(status().isOk());

        advanceTo(Instant.parse("2026-10-10T15:00:00Z").plusSeconds(61));
        mockMvc.perform(get("/v1/exports/backup")).andExpect(status().isOk());
    }

    private String otherBearer() {
        java.util.UUID other = java.util.UUID.randomUUID();
        committed(() -> com.moneylog.util.SqlFixtures.insertUser(jdbc, other, "limite-" + other + "@moneylog.com"));
        return auth.bearer(other);
    }
}
