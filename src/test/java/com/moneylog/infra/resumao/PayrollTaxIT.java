package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

@DisplayName("F2: INSS e IRRF com valores calculados à mão a partir das tabelas oficiais citadas, e vigências distintas (INSS pela competência, IRRF pela data de pagamento)")
class PayrollTaxIT extends ResumaoItBase {

    private static final String PROFILE_TEMPLATE = "{\"source\":\"Empresa Exemplo\",\"gross\":\"%s\",\"dependents\":%d,\"advance\":null,"
            + "\"balanceDayOfMonth\":1,\"deductions\":[],\"thirteenth\":{\"enabled\":false,\"firstInstallmentMonth\":11}}";

    private JsonNode preview(String gross, int dependents, String month) throws Exception {
        String id = createdBody(post("/v1/income-profiles", String.format(PROFILE_TEMPLATE, gross, dependents))).get("id").asString();
        return okBody(get("/v1/income-profiles/" + id + "/preview?month=" + month));
    }

    private void assertTaxes(JsonNode preview, String inss, String irrf, String net) {
        assertThat(money(preview, "inss")).as("INSS").isEqualTo(inss);
        assertThat(money(preview, "irrf")).as("IRRF").isEqualTo(irrf);
        assertThat(money(preview, "net")).as("líquido").isEqualTo(net);
        assertThat(cents(preview.get("balance").get("amount")) + (preview.get("advance").isNull() ? 0 : cents(preview.get("advance").get("amount"))))
                .as("adiantamento + saldo = líquido").isEqualTo(cents(preview.get("net")));
    }

    @Test
    @DisplayName("bruto 3.000,00 sem dependentes: INSS 121,575 + 115,3656 + 11,6592 = 248,60; IRRF 0 (base 2.392,80 na faixa isenta); líquido 2.751,40")
    void brutoTresMil() throws Exception {
        assertTaxes(preview("3000.00", 0, "2026-11"), "248.60", "0.00", "2751.40");
    }

    @Test
    @DisplayName("bruto 5.000,00 com 1 dependente (deduções legais 691,10 > simplificado): imposto 294,01 zerado pelo redutor de 312,89; INSS 501,51; líquido 4.498,49")
    void brutoCincoMilComDependente() throws Exception {
        assertTaxes(preview("5000.00", 1, "2026-11"), "501.51", "0.00", "4498.49");
    }

    @Test
    @DisplayName("bruto 5.500,00 (simplificado 607,20 > INSS 571,51): base 4.892,80 a 27,5% menos 908,73 = 436,79; redutor 978,62 - 0,133145 x 5.500 = 246,32; IRRF 190,47; líquido 4.738,02")
    void descontoSimplificadoEFaixaDoRedutor() throws Exception {
        assertTaxes(preview("5500.00", 0, "2026-11"), "571.51", "190.47", "4738.02");
    }

    @Test
    @DisplayName("bruto 6.000,00 na faixa do redutor: IRRF 564,85 - redutor 179,75 = 385,10; INSS 641,51; líquido 4.973,39")
    void brutoSeisMil() throws Exception {
        assertTaxes(preview("6000.00", 0, "2026-11"), "641.51", "385.10", "4973.39");
    }

    @Test
    @DisplayName("bruto 8.000,00 sem redutor: INSS 921,51; IRRF 1.037,85; com 2 dependentes a base cai 379,18 e o IRRF vai a 933,58")
    void brutoOitoMilComEmSemDependentes() throws Exception {
        assertTaxes(preview("8000.00", 0, "2026-11"), "921.51", "1037.85", "6040.64");
        assertTaxes(preview("8000.00", 2, "2026-11"), "921.51", "933.58", "6144.91");
    }

    @Test
    @DisplayName("bruto 12.000,00: o INSS para no teto de 8.475,55 (988,09) e o IRRF é 2.119,55; líquido 8.892,36")
    void tetoDoInss() throws Exception {
        assertTaxes(preview("12000.00", 0, "2026-11"), "988.09", "2119.55", "8892.36");
    }

    @Test
    @DisplayName("GET /v1/tax-tables?year=2026 lista INSS e IRRF com vigência, base, método e fonte oficial; ano sem tabela devolve lista vazia; só leitura; sem token 401")
    void tabelasSomenteLeitura() throws Exception {
        JsonNode tables = okBody(get("/v1/tax-tables?year=2026"));
        assertThat(tables.get("year").asInt()).isEqualTo(2026);
        assertThat(tables.get("items")).hasSizeGreaterThanOrEqualTo(2);
        for (JsonNode table : tables.get("items")) {
            assertThat(table.get("sources")).isNotEmpty();
            assertThat(table.get("sources").get(0).get("url").asString()).startsWith("https://");
            assertThat(table.get("basis").asString()).isEqualTo(table.get("kind").asString().equals("INSS") ? "COMPETENCE" : "PAYMENT_DATE");
        }
        assertThat(okBody(get("/v1/tax-tables?year=2001")).get("items")).isEmpty();
        mockMvc.perform(get("/v1/tax-tables?year=1899")).andExpect(status().isBadRequest());
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/tax-tables?year=2026")).andExpect(status().isUnauthorized());
        mockMvc.perform(post("/v1/tax-tables?year=2026", "{}")).andExpect(status().is4xxClientError());
    }

    private void insertTable(String id, String kind, int version, String from, int rateBps, long ceilingCents) {
        committed(() -> {
            jdbc.update("INSERT INTO tax_tables (id, kind, version, effective_from, basis, method, contribution_ceiling_cents, dependent_deduction_cents, "
                    + "simplified_limit_cents) VALUES (?::uuid, ?, ?, ?::date, ?, ?, ?, ?, ?)", id, kind, version, from,
                    kind.equals("INSS") ? "COMPETENCE" : "PAYMENT_DATE", kind.equals("INSS") ? "PROGRESSIVE_BY_BAND" : "RATE_MINUS_DEDUCTION",
                    kind.equals("INSS") ? ceilingCents : null, kind.equals("IRRF") ? 0L : null, kind.equals("IRRF") ? 0L : null);
            jdbc.update("INSERT INTO tax_table_brackets (tax_table_id, position, from_cents, up_to_cents, rate_bps, deduction_cents) VALUES (?::uuid, 1, 0, ?, ?, 0)",
                    id, kind.equals("INSS") ? ceilingCents : null, rateBps);
            jdbc.update("INSERT INTO tax_table_sources (tax_table_id, position, agency, norm, url, consulted_on) VALUES (?::uuid, 1, ?, ?, ?, DATE '2026-10-15')",
                    id, "TABELA FICTICIA DE TESTE", "Norma ficticia", "https://exemplo.invalid/tabela");
        });
    }

    @AfterEach
    void limparTabelasFicticias() {
        committed(() -> {
            jdbc.update("DELETE FROM tax_table_brackets WHERE tax_table_id IN (SELECT id FROM tax_tables WHERE effective_from >= DATE '2030-01-01')");
            jdbc.update("DELETE FROM tax_table_sources WHERE tax_table_id IN (SELECT id FROM tax_tables WHERE effective_from >= DATE '2030-01-01')");
            jdbc.update("DELETE FROM tax_tables WHERE effective_from >= DATE '2030-01-01'");
        });
    }

    @Test
    @DisplayName("vigências distintas (tabelas fictícias rotuladas): na competência 2030-02 pago em 01/03, o INSS usa a tabela de janeiro (10%) e o IRRF a de março (20%): INSS 1.000,00, IRRF 1.800,00, líquido 7.200,00")
    void vigenciasDistintasNoMesDeTransicao() throws Exception {
        insertTable(UUID.randomUUID().toString(), "INSS", 1, "2030-01-01", 1000, 1_000_000_00L);
        insertTable(UUID.randomUUID().toString(), "INSS", 1, "2030-03-01", 2000, 1_000_000_00L);
        insertTable(UUID.randomUUID().toString(), "IRRF", 1, "2030-01-01", 1000, 0L);
        insertTable(UUID.randomUUID().toString(), "IRRF", 1, "2030-03-01", 2000, 0L);

        JsonNode transition = preview("10000.00", 0, "2030-02");
        assertThat(okBody(get("/v1/tax-tables?year=2030")).get("items").size()).as("as quatro fictícias mais as de 2026 ainda vigentes").isEqualTo(6);

        assertTaxes(transition, "1000.00", "1800.00", "7200.00");
        JsonNode refs = transition.get("taxTables");
        assertThat(refs).hasSize(2);
        for (JsonNode ref : refs) {
            assertThat(ref.get("effectiveFrom").asString()).isEqualTo(ref.get("kind").asString().equals("INSS") ? "2030-01-01" : "2030-03-01");
        }
        assertTaxes(preview("10000.00", 0, "2030-03"), "2000.00", "1600.00", "6400.00");
    }

    @Test
    @DisplayName("correção de tabela é versão nova na mesma vigência: a maior versão vale e a anterior continua na lista; a tabela aplicada não é editada")
    void correcaoPorVersaoNova() throws Exception {
        insertTable(UUID.randomUUID().toString(), "INSS", 1, "2030-01-01", 1000, 1_000_000_00L);
        insertTable(UUID.randomUUID().toString(), "IRRF", 1, "2030-01-01", 1000, 0L);
        assertTaxes(preview("10000.00", 0, "2030-02"), "1000.00", "900.00", "8100.00");

        insertTable(UUID.randomUUID().toString(), "INSS", 2, "2030-01-01", 1500, 1_000_000_00L);

        assertTaxes(preview("10000.00", 0, "2030-02"), "1500.00", "850.00", "7650.00");
        long versions = okBody(get("/v1/tax-tables?year=2030")).get("items").size();
        assertThat(versions).isGreaterThanOrEqualTo(2L);
    }
}
