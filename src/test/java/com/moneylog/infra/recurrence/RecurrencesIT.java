package com.moneylog.infra.recurrence;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.MvcResult;

import tools.jackson.databind.JsonNode;

import com.moneylog.infra.debt.E4ItBase;
import com.moneylog.util.ContractValidation;
import com.moneylog.util.SqlFixtures;

@DisplayName("Recorrências: persistência, CRUD, ocorrências e custo fixo (T79)")
class RecurrencesIT extends E4ItBase {

    private String body(String type, String amount, String description, String category, String target, String frequency, String start,
                        String extra) {
        return "{\"type\":\"" + type + "\",\"amount\":\"" + amount + "\",\"description\":\"" + description + "\",\"categoryId\":\"" + category + "\","
                + target + ",\"frequency\":\"" + frequency + "\",\"startDate\":\"" + start + "\"" + (extra == null ? "" : "," + extra) + "}";
    }

    private String account() {
        return "\"accountId\":\"" + nubank + "\",\"method\":\"PIX\"";
    }

    protected JsonNode internet(String start) throws Exception {
        return createdBody(post("/v1/recurrences", body("EXPENSE", "119.00", "Internet", bills, account(), "MONTHLY", start, null)));
    }

    private JsonNode occurrences(String query) throws Exception {
        return okBody(get("/v1/recurrences/occurrences?" + query));
    }

    @Test
    @DisplayName("CA1: POST mensal de 119,00 \"Internet\" (Contas, Nubank, PIX), início 10/09: 201 com monthlyEstimate; de 01/10 a 31/12 as ocorrências PENDING são 10/10, 10/11 e 10/12")
    void deveCriarERecorrenciaMensal() throws Exception {
        MvcResult result = mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "119.00", "Internet", bills, account(), "MONTHLY", "2026-09-10", null)))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.type").value("EXPENSE"))
                .andExpect(jsonPath("$.amount.amount").value("119.00")).andExpect(jsonPath("$.monthlyEstimate.amount").value("119.00"))
                .andExpect(jsonPath("$.category.name").value("Contas")).andExpect(jsonPath("$.account.name").value("Nubank"))
                .andExpect(jsonPath("$.card").doesNotExist()).andExpect(jsonPath("$.method").value("PIX"))
                .andExpect(jsonPath("$.frequency").value("MONTHLY")).andExpect(jsonPath("$.interval").value(1))
                .andExpect(jsonPath("$.unit").doesNotExist()).andExpect(jsonPath("$.active").value(true))
                .andExpect(jsonPath("$.nextOccurrence").value("2026-09-10")).andExpect(jsonPath("$.version").value(0)).andReturn();
        JsonNode created = body(result);
        assertThat(result.getResponse().getHeader("Location")).isEqualTo("/v1/recurrences/" + created.get("id").asString());

        JsonNode window = occurrences("from=2026-10-01&to=2026-12-31");
        assertThat(window.get("hasNext").asBoolean()).isFalse();
        assertThat(window.get("items")).hasSize(3);
        assertThat(window.get("items").get(0).get("date").asString()).isEqualTo("2026-10-10");
        assertThat(window.get("items").get(1).get("date").asString()).isEqualTo("2026-11-10");
        assertThat(window.get("items").get(2).get("date").asString()).isEqualTo("2026-12-10");
        assertThat(window.get("items")).allSatisfy(o -> assertThat(o.get("status").asString()).isEqualTo("PENDING"));
        assertThat(window.get("items").get(0).get("overdue").asBoolean()).isFalse();
        assertThat(money(window.get("items").get(0), "amount")).isEqualTo("119.00");
        assertThat(window.get("items").get(0).get("direction").asString()).isEqualTo("OUT");

        JsonNode pending = occurrences("status=PENDING&to=2026-12-31");
        assertThat(pending.get("items")).hasSize(4);
        assertThat(pending.get("items").get(0).get("date").asString()).isEqualTo("2026-09-10");
        assertThat(pending.get("items").get(0).get("overdue").asBoolean()).isTrue();
        assertThat(count("transactions")).isZero();
        assertThat(money(summary().get("month"), "consumption")).isEqualTo("0.00");
        assertThat(okBody(get("/v1/recurrences/" + created.get("id").asString())).get("id").asString()).isEqualTo(created.get("id").asString());
    }

    @Test
    @DisplayName("CA2: dia 31 em meses curtos (31/01, 28/02, 31/03, 30/04), maxOccurrences e endDate limitam, CUSTOM a cada 2 semanas funciona")
    void deveCalcularAAgenda() throws Exception {
        createdBody(post("/v1/recurrences", body("EXPENSE", "10.00", "Mensal 31", bills, account(), "MONTHLY", "2026-01-31", "\"maxOccurrences\":4")));
        JsonNode days = occurrences("from=2026-01-01&to=2026-12-31&status=PENDING");
        assertThat(days.get("items")).extracting(o -> o.get("date").asString()).containsExactly("2026-01-31", "2026-02-28", "2026-03-31", "2026-04-30");

        JsonNode custom = createdBody(post("/v1/recurrences", body("EXPENSE", "20.00", "Quinzenal", bills, account(), "CUSTOM", "2026-11-01",
                "\"interval\":2,\"unit\":\"WEEKS\",\"endDate\":\"2026-12-31\"")));
        assertThat(custom.get("interval").asInt()).isEqualTo(2);
        assertThat(custom.get("unit").asString()).isEqualTo("WEEKS");
        JsonNode weeks = occurrences("from=2026-11-01&to=2027-02-01&status=PENDING");
        assertThat(weeks.get("items").toString()).contains("2026-11-01", "2026-11-15", "2026-11-29", "2026-12-13", "2026-12-27");
        assertThat(weeks.get("items").toString()).doesNotContain("2027-01-10");

        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "20.00", "Sem unidade", bills, account(), "CUSTOM", "2026-11-01", "\"interval\":2")))
                .andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "20.00", "Intervalo", bills, account(), "MONTHLY", "2026-11-01", "\"interval\":2")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "20.00", "Fim", bills, account(), "MONTHLY", "2026-11-01", "\"endDate\":\"2026-10-01\"")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "20.00", "Zero", bills, account(), "MONTHLY", "2026-11-01", "\"maxOccurrences\":0")))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly());
        mockMvc.perform(post("/v1/recurrences", body("CARD_PURCHASE", "49.90", "Streaming", subscriptions, "\"cardId\":\"" + card + "\"", "MONTHLY",
                "2026-10-15", "\"installments\":3"))).andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly())
                .andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
    }

    @Test
    @DisplayName("CA3: assinatura à vista no cartão é aceita; conta e cartão juntos, cartão com conta/método, despesa com cartão e sem destino são 422 RECURRENCE_INVALID; conta arquivada, que não é CASH, categoria de tipo errado")
    void deveValidarOTipoEOsDestinos() throws Exception {
        JsonNode subscription = createdBody(post("/v1/recurrences", body("CARD_PURCHASE", "49.90", "Streaming", subscriptions,
                "\"cardId\":\"" + card + "\"", "MONTHLY", "2026-09-15", null)));
        assertThat(subscription.get("type").asString()).isEqualTo("CARD_PURCHASE");
        assertThat(subscription.get("card").get("name").asString()).isEqualTo("Roxinho");
        assertThat(subscription.get("account").isNull()).isTrue();
        assertThat(subscription.get("method").isNull()).isTrue();

        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Os dois", bills, account() + ",\"cardId\":\"" + card + "\"", "MONTHLY", "2026-10-10", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("CARD_PURCHASE", "10.00", "Cartão com conta", subscriptions, "\"cardId\":\"" + card + "\",\"accountId\":\""
                        + nubank + "\"", "MONTHLY", "2026-10-10", null))).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("CARD_PURCHASE", "10.00", "Cartão com método", subscriptions, "\"cardId\":\"" + card + "\",\"method\":\"PIX\"",
                "MONTHLY", "2026-10-10", null))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Despesa no cartão", bills, "\"cardId\":\"" + card + "\"", "MONTHLY", "2026-10-10", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Sem destino", bills, "\"method\":\"PIX\"", "MONTHLY", "2026-10-10", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences", body("INCOME", "10.00", "Receita em categoria de despesa", bills, account(), "MONTHLY", "2026-10-10", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("CATEGORY_KIND_MISMATCH"));
        String invest = createdBody(post("/v1/accounts", "{\"name\":\"Renda fixa\",\"type\":\"INVESTMENT\",\"openingBalance\":{\"amount\":\"0.00\","
                + "\"currency\":\"BRL\"},\"openingDate\":\"2026-10-01\"}")).get("id").asString();
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Investimento", bills, "\"accountId\":\"" + invest + "\",\"method\":\"PIX\"", "MONTHLY",
                "2026-10-10", null))).andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("TRANSACTION_ACCOUNT_NOT_CASH"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Conta inexistente", bills, "\"accountId\":\"" + UUID.randomUUID() + "\",\"method\":\"PIX\"",
                "MONTHLY", "2026-10-10", null))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ACCOUNT_NOT_FOUND"));
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Categoria inexistente", UUID.randomUUID().toString(), account(), "MONTHLY",
                "2026-10-10", null))).andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("CATEGORY_NOT_FOUND"));
        mockMvc.perform(post("/v1/accounts/" + invest + "/archive", "")).andExpect(status().isOk());
        assertThat(count("recurring_transactions")).isEqualTo(1);
    }

    @Test
    @DisplayName("conta a definir (2.14.0): despesa sem conta nem método é aceita; método sem conta é 422; confirmar exige conta e método (RECURRENCE_ACCOUNT_REQUIRED) e, com eles, cria a despesa na data")
    void deveAceitarContaADefinir() throws Exception {
        JsonNode created = createdBody(post("/v1/recurrences", body("EXPENSE", "79.99", "Celular", bills, "\"method\":null", "MONTHLY", "2026-10-07", null)));
        assertThat(created.get("account").isNull()).isTrue();
        assertThat(created.get("method").isNull()).isTrue();
        String id = created.get("id").asString();
        mockMvc.perform(post("/v1/recurrences", body("EXPENSE", "10.00", "Método sem conta", bills, "\"method\":\"PIX\"", "MONTHLY", "2026-10-10", null)))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-10-07/realize", "{}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isUnprocessableEntity()).andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("RECURRENCE_ACCOUNT_REQUIRED"));
        assertThat(count("transactions")).isZero();
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-10-07/realize", "{\"accountId\":\"" + nubank + "\",\"method\":\"PIX\"}")
                .header("Idempotency-Key", UUID.randomUUID().toString())).andExpect(status().isCreated()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.type").value("EXPENSE")).andExpect(jsonPath("$.amount.amount").value("79.99"));
        assertThat(count("transactions")).isEqualTo(1);
    }

    @Test
    @DisplayName("CA4: PUT muda valor e categoria das ocorrências não realizadas; as realizadas ficam; frequência no corpo é 400; version velha é 409")
    void deveEditarOModelo() throws Exception {
        String id = internet("2026-10-10").get("id").asString();
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-10-10/realize", "{}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isCreated()).andExpect(ContractValidation.conforms());
        String update = "{\"amount\":\"129.90\",\"description\":\"Internet fibra\",\"categoryId\":\"" + subscriptions + "\"," + account() + ",\"version\":0}";
        mockMvc.perform(put("/v1/recurrences/" + id, update)).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.amount.amount").value("129.90")).andExpect(jsonPath("$.category.name").value("Assinaturas"))
                .andExpect(jsonPath("$.description").value("Internet fibra")).andExpect(jsonPath("$.version").value(1))
                .andExpect(jsonPath("$.monthlyEstimate.amount").value("129.90")).andExpect(jsonPath("$.nextOccurrence").value("2026-11-10"));

        JsonNode list = occurrences("from=2026-10-01&to=2026-12-31");
        assertThat(list.get("items")).hasSize(3);
        assertThat(list.get("items").get(0).get("status").asString()).isEqualTo("REALIZED");
        assertThat(money(list.get("items").get(0), "amount")).isEqualTo("119.00");
        assertThat(list.get("items").get(0).get("transactionId").isNull()).isFalse();
        assertThat(money(list.get("items").get(1), "amount")).isEqualTo("129.90");
        assertThat(money(list.get("items").get(2), "amount")).isEqualTo("129.90");
        assertThat(jdbc.queryForObject("SELECT amount_cents FROM transactions WHERE recurrence_id = ?::uuid", Long.class, id)).isEqualTo(11_900L);

        mockMvc.perform(put("/v1/recurrences/" + id, update.replace("\"version\":0", "\"frequency\":\"WEEKLY\",\"version\":1")))
                .andExpect(status().isBadRequest()).andExpect(ContractValidation.responseOnly()).andExpect(jsonPath("$.errors[0].code").value("UNKNOWN_FIELD"));
        mockMvc.perform(put("/v1/recurrences/" + id, update.replace("\"version\":0", "\"startDate\":\"2026-01-01\",\"version\":1")))
                .andExpect(status().isBadRequest());
        mockMvc.perform(put("/v1/recurrences/" + id, update)).andExpect(status().isConflict()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("VERSION_CONFLICT"));
        mockMvc.perform(put("/v1/recurrences/" + id, update.replace("\"version\":0", "\"endDate\":\"2026-10-05\",\"version\":1")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(put("/v1/recurrences/" + id, update.replace("\"version\":0", "\"endDate\":\"2026-12-31\",\"version\":1")))
                .andExpect(status().isOk()).andExpect(jsonPath("$.endDate").value("2026-12-31"));
        mockMvc.perform(put("/v1/recurrences/" + id, update.replace("\"version\":0", "\"cardId\":\"" + card + "\",\"version\":2")))
                .andExpect(status().isUnprocessableEntity()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
    }

    @Test
    @DisplayName("summary com recorrência de cartão rateada 70/30: padrão e MINE somam 70,00, GROSS soma 100,00, devolvem view; view inválida é 400")
    void deveResumirNasDuasVisoes() throws Exception {
        String shares = "\"splitMode\":\"BPS\",\"shares\":[{\"personId\":null,\"bps\":7000},{\"personId\":\"" + joao + "\",\"bps\":3000}]";
        createdBody(post("/v1/recurrences", body("CARD_PURCHASE", "100.00", "Streaming", subscriptions, "\"cardId\":\"" + card + "\"", "MONTHLY", "2026-10-10", shares)));

        JsonNode padrao = okBody(get("/v1/recurrences/summary"));
        JsonNode mine = okBody(get("/v1/recurrences/summary?view=MINE"));
        JsonNode gross = okBody(get("/v1/recurrences/summary?view=GROSS"));

        assertThat(padrao.get("view").asString()).isEqualTo("MINE");
        assertThat(money(padrao, "fixedCosts")).isEqualTo("70.00");
        assertThat(mine.get("view").asString()).isEqualTo("MINE");
        assertThat(money(mine, "fixedCosts")).isEqualTo("70.00");
        assertThat(gross.get("view").asString()).isEqualTo("GROSS");
        assertThat(money(gross, "fixedCosts")).isEqualTo("100.00");
        mockMvc.perform(get("/v1/recurrences/summary?view=INVALID")).andExpect(status().isBadRequest());
    }

    @Test
    @DisplayName("CA5: summary traz custo fixo e receita recorrente com estimate true (semanal × 52/12); desativada não entra")
    void deveResumirOCustoFixo() throws Exception {
        internet("2026-10-10");
        String weekly = createdBody(post("/v1/recurrences", body("EXPENSE", "100.00", "Diarista", bills, account(), "WEEKLY", "2026-10-12", null))).get("id").asString();
        createdBody(post("/v1/recurrences", body("INCOME", "5000.00", "Salário", salary, account(), "MONTHLY", "2026-10-05", null)));
        JsonNode summary = okBody(get("/v1/recurrences/summary"));
        assertThat(summary.get("estimate").asBoolean()).isTrue();
        assertThat(money(summary, "fixedCosts")).isEqualTo("552.33");
        assertThat(money(summary, "recurringIncome")).isEqualTo("5000.00");

        mockMvc.perform(post("/v1/recurrences/" + weekly + "/deactivate", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.active").value(false)).andExpect(jsonPath("$.monthlyEstimate.amount").value("0.00"))
                .andExpect(jsonPath("$.nextOccurrence").doesNotExist());
        assertThat(money(okBody(get("/v1/recurrences/summary")), "fixedCosts")).isEqualTo("119.00");
        mockMvc.perform(post("/v1/recurrences/" + weekly + "/deactivate", "")).andExpect(status().isOk()).andExpect(jsonPath("$.active").value(false));
        JsonNode actives = okBody(get("/v1/recurrences?active=true"));
        assertThat(actives.get("items")).hasSize(2);
        assertThat(okBody(get("/v1/recurrences")).get("items")).hasSize(3);
        assertThat(okBody(get("/v1/recurrences?active=false")).get("items")).hasSize(1);
        JsonNode firstPage = okBody(get("/v1/recurrences?pageSize=2"));
        assertThat(firstPage.get("items")).hasSize(2);
        assertThat(firstPage.get("hasNext").asBoolean()).isTrue();
    }

    @Test
    @DisplayName("CA6: GET /v1/dashboard/upcoming traz as ocorrências PENDING como RECURRENCE (de hoje a 12 meses; as vencidas e pendentes entram, S-20); desativada ou encerrada não")
    void deveSerFonteDeCompromissos() throws Exception {
        String id = internet("2026-10-10").get("id").asString();
        JsonNode upcoming = upcoming("from=2026-10-10&to=2026-12-31");
        assertThat(upcoming.get("items")).hasSize(3);
        assertThat(upcoming.get("items")).allSatisfy(item -> {
            assertThat(item.get("kind").asString()).isEqualTo("RECURRENCE");
            assertThat(item.get("refType").asString()).isEqualTo("RECURRENCE");
            assertThat(item.get("refId").asString()).isEqualTo(id);
            assertThat(item.get("direction").asString()).isEqualTo("OUT");
        });
        assertThat(upcoming.get("items").get(0).get("bucket").asString()).isEqualTo("TODAY");
        assertThat(money(upcoming.get("totals"), "out")).isEqualTo("357.00");
        JsonNode summary = summary();
        assertThat(money(summary.get("committed"), "d30")).isEqualTo("119.00");
        assertThat(money(summary.get("committed"), "d60")).isEqualTo("238.00");
        assertThat(money(summary.get("month"), "consumption")).isEqualTo("0.00");

        String old = createdBody(post("/v1/recurrences", body("EXPENSE", "50.00", "Antiga", bills, account(), "MONTHLY", "2026-09-20", "\"endDate\":\"2026-10-31\"")))
                .get("id").asString();
        assertThat(money(summary().get("committed"), "d7")).isEqualTo("169.00");
        mockMvc.perform(post("/v1/recurrences/" + old + "/end", "{\"endDate\":\"2026-09-30\"}")).andExpect(status().isOk())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.endDate").value("2026-09-30"));
        assertThat(money(summary().get("committed"), "d7")).isEqualTo("169.00");
        mockMvc.perform(post("/v1/recurrences/" + old + "/deactivate", "")).andExpect(status().isOk());
        assertThat(money(summary().get("committed"), "d7")).isEqualTo("119.00");
        mockMvc.perform(post("/v1/recurrences/" + id + "/deactivate", "")).andExpect(status().isOk());
        assertThat(upcoming("from=2026-10-10&to=2026-12-31").get("items")).isEmpty();
        assertThat(money(summary().get("committed"), "m12")).isEqualTo("0.00");
    }

    @Test
    @DisplayName("encerrar: sem corpo usa hoje; data anterior ao início ou à última realizada é 422; as ocorrências depois do fim somem")
    void deveEncerrar() throws Exception {
        String id = internet("2026-09-10").get("id").asString();
        mockMvc.perform(post("/v1/recurrences/" + id + "/end", "")).andExpect(status().isOk()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.endDate").value("2026-10-10"));
        assertThat(occurrences("from=2026-10-11&to=2026-12-31").get("items")).isEmpty();
        mockMvc.perform(post("/v1/recurrences/" + id + "/end", "{\"endDate\":\"2026-08-01\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(ContractValidation.conforms()).andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
        mockMvc.perform(post("/v1/recurrences/" + id + "/occurrences/2026-09-10/skip", "")).andExpect(status().isOk());
        mockMvc.perform(post("/v1/recurrences/" + id + "/end", "{\"endDate\":\"2026-09-09\"}")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("RECURRENCE_INVALID"));
    }

    @Test
    @DisplayName("CA7: recorrência de outro usuário é 404 em todas as rotas e não entra no /upcoming nem no summary; sem token é 401")
    void deveIsolarPorUsuario() throws Exception {
        String id = internet("2026-10-10").get("id").asString();
        UUID other = UUID.randomUUID();
        committed(() -> SqlFixtures.insertUser(jdbc, other, "outro-" + other + "@example.com"));
        String otherBearer = auth.bearer(other);
        mockMvc.perform(getAs(otherBearer, "/v1/recurrences/" + id)).andExpect(status().isNotFound()).andExpect(ContractValidation.conforms())
                .andExpect(jsonPath("$.code").value("RECURRENCE_NOT_FOUND"));
        mockMvc.perform(putAs(otherBearer, "/v1/recurrences/" + id, "{\"amount\":\"1.00\",\"description\":\"x\",\"categoryId\":\"" + bills + "\",\"version\":0}"))
                .andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/recurrences/" + id + "/end", "{}")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/recurrences/" + id + "/deactivate", "")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/recurrences/" + id + "/occurrences/2026-10-10/skip", "")).andExpect(status().isNotFound());
        mockMvc.perform(postAs(otherBearer, "/v1/recurrences/" + id + "/occurrences/2026-10-10/realize", "{}").header("Idempotency-Key", UUID.randomUUID().toString()))
                .andExpect(status().isNotFound());
        assertThat(body(mockMvc.perform(getAs(otherBearer, "/v1/recurrences")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        assertThat(body(mockMvc.perform(getAs(otherBearer, "/v1/recurrences/occurrences")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        assertThat(body(mockMvc.perform(getAs(otherBearer, "/v1/dashboard/upcoming?from=2026-10-10&to=2026-12-31")).andExpect(status().isOk())
                .andReturn()).get("items")).isEmpty();
        assertThat(body(mockMvc.perform(getAs(otherBearer, "/v1/recurrences/summary")).andExpect(status().isOk()).andReturn()).get("fixedCosts")
                .get("amount").asString()).isEqualTo("0.00");
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/recurrences")).andExpect(status().isUnauthorized());
    }
}
