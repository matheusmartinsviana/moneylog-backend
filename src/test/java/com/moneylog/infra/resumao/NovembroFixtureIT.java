package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.databind.JsonNode;

@DisplayName("T0: fixtures sintéticas de novembro/2026 carregam pela API e o razão fecha em zero")
class NovembroFixtureIT extends ResumaoItBase {

    @Test
    @DisplayName("o cenário inteiro é criado, soma zero, e o resumo do mês responde")
    void cenarioCarrega() throws Exception {
        Map<String, String> ids = loadFixture("novembro-2026");

        assertThat(ids).containsKeys("contaA", "cartaoA", "cartaoB", "compraTerco", "compraMetade", "grupoNotebook", "assinaturaUsd",
                "assinaturaSemanal", "perfilRenda", "imovel", "pixNoCredito", "debtFolha");
        assertThat(ledgerTotal()).isZero();
        JsonNode statement = okBody(get("/v1/owner-statement?from=2026-11&months=1"));
        assertThat(statement.get("months")).hasSize(1);
        assertThat(money(statement, "openingBalance")).isEqualTo("9000.00");
        assertThat(count("transactions")).isEqualTo(7L);
        assertThat(count("recurring_transactions")).isEqualTo(5L);
        assertThat(count("income_profiles")).isEqualTo(1L);
        assertThat(count("tracked_commitments")).isEqualTo(1L);
        assertThat(count("purchase_groups")).isEqualTo(1L);
    }
}
