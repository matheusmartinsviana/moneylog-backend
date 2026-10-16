package com.moneylog.infra.resumao;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.node.ObjectNode;

import com.moneylog.core.notification.app.usecase.NotificationUseCases.GenerateNotificationsUseCase;

@DisplayName("I3: geradores de alerta novos ponta a ponta (relógio controlado, sem IA, sem duplicar no mesmo dia)")
class AlertsIT extends ResumaoItBase {

    @Autowired
    private GenerateNotificationsUseCase generate;

    @BeforeEach
    void mundo() throws Exception {
        basicWorld();
    }

    private void generateNow() {
        committed(() -> generate.handle(userId));
    }

    private List<JsonNode> notifications(String kind) throws Exception {
        List<JsonNode> result = new ArrayList<>();
        for (JsonNode item : okBody(get("/v1/notifications?status=ALL&kind=" + kind + "&pageSize=50")).get("items")) {
            result.add(item);
        }
        return result;
    }

    private String cardWith(String name, String autoDebit) throws Exception {
        return createdBody(post("/v1/cards", "{\"name\":\"" + name + "\",\"closingDay\":25,\"dueDay\":5,\"limit\":{\"amount\":\"50000.00\",\"currency\":\"BRL\"},"
                + "\"defaultPaymentAccountId\":\"" + accountA + "\"" + (autoDebit == null ? "" : ",\"autoDebit\":" + autoDebit) + "}")).get("id").asString();
    }

    private void disableKind(String kind) throws Exception {
        ObjectNode settings = (ObjectNode) okBody(get("/v1/settings/notifications"));
        ((ObjectNode) settings.get("kinds")).put(kind, false);
        mockMvc.perform(put("/v1/settings/notifications", settings.toString())).andExpect(status().isOk());
    }

    private void saveAlertSettings(String alertsJson) throws Exception {
        ObjectNode current = (ObjectNode) okBody(get("/v1/settings/notifications"));
        current.set("alerts", objectMapper.readTree(alertsJson));
        mockMvc.perform(put("/v1/settings/notifications", current.toString())).andExpect(status().isOk());
    }

    @Test
    @DisplayName("fatura vence em até 3 dias com autoDebit = false: INVOICE_DUE_AUTODEBIT_OFF (uma vez, mesmo rodando duas vezes); com true ou null só o INVOICE_DUE de sempre")
    void faturaSemDebitoAutomatico() throws Exception {
        String off = cardWith("Cartao sem debito", "false");
        assertThat(okBody(get("/v1/cards/" + off)).get("autoDebit").asBoolean()).as("o cartão guarda autoDebit = false").isFalse();
        String on = cardWith("Cartao com debito", "true");
        String unknown = cardWith("Cartao desconhecido", null);
        for (String card : List.of(off, on, unknown)) {
            created(sharedPurchase(card, "100.00", 1, "2026-10-10", null, null));
        }
        advanceTo(Instant.parse("2026-11-02T12:00:00Z"));
        disableKind("NEGATIVE_MONTH_PROJECTED");

        generateNow();
        generateNow();

        List<JsonNode> alerts = notifications("INVOICE_DUE_AUTODEBIT_OFF");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("params").get("cardName").asString()).isEqualTo("Cartao sem debito");
        assertThat(alerts.get(0).get("params").get("dueDate").asString()).isEqualTo("2026-11-05");
        assertThat(notifications("INVOICE_DUE")).hasSize(3);
    }

    @Test
    @DisplayName("antecedência da fatura (3 dias, configurável): 4 dias antes não avisa, no dia e depois do vencimento também não")
    void antecedenciaDaFatura() throws Exception {
        cardWith("Cartao sem debito", "false");
        created(sharedPurchase(jdbc.queryForObject("SELECT id::text FROM credit_cards WHERE user_id = ? AND name = 'Cartao sem debito'", String.class, userId),
                "100.00", 1, "2026-10-10", null, null));

        advanceTo(Instant.parse("2026-11-01T12:00:00Z"));
        generateNow();
        assertThat(notifications("INVOICE_DUE_AUTODEBIT_OFF")).as("4 dias antes").isEmpty();
        saveAlertSettings("{\"subscriptionUnusedDays\":null,\"invoiceDueLeadDays\":5,\"documentDivergenceTolerance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"}}");
        advanceTo(Instant.parse("2026-11-01T13:00:00Z"));
        generateNow();
        assertThat(notifications("INVOICE_DUE_AUTODEBIT_OFF")).as("com antecedência 5").hasSize(1);
        advanceTo(Instant.parse("2026-11-07T12:00:00Z"));
        generateNow();
        assertThat(notifications("INVOICE_DUE_AUTODEBIT_OFF")).as("depois do vencimento não repete").hasSize(1);
    }

    @Test
    @DisplayName("mês projetado negativo: NEGATIVE_MONTH_PROJECTED com o mês e a sobra, uma vez; sem negativo, nenhum")
    void mesNegativo() throws Exception {
        generateNow();
        assertThat(notifications("NEGATIVE_MONTH_PROJECTED")).isEmpty();
        created(sharedPurchase(cardA, "600.00", 3, "2026-10-05", null, null));

        generateNow();

        List<JsonNode> alerts = notifications("NEGATIVE_MONTH_PROJECTED");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("params").get("competence").asString()).isEqualTo("2026-11");
        assertThat(alerts.get(0).get("params").get("surplus").asString()).startsWith("-");
    }

    @Test
    @DisplayName("financiamento pendente: nova compra parcelada gera NEW_DEBT_BEFORE_FINANCING após gravar e FINANCING_PENDING na prévia; sem financiamento, nenhum dos dois")
    void dividaNovaAntesDoFinanciamento() throws Exception {
        advanceTo(Instant.parse("2026-10-14T12:00:00Z"));
        created(sharedPurchase(cardA, "200.00", 2, "2026-10-10", null, null));
        generateNow();
        advanceTo(Instant.parse("2026-10-15T12:00:00Z"));
        assertThat(notifications("NEW_DEBT_BEFORE_FINANCING")).isEmpty();

        createdBody(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Casa Exemplo\",\"total\":\"300000.00\",\"schedule\":["
                + "{\"label\":\"Financiamento\",\"type\":\"FINANCING\",\"dueDate\":\"2027-06-10\",\"amount\":\"300000.00\",\"paid\":false}]}"));
        created(sharedPurchase(cardA, "400.00", 4, "2026-10-12", null, null));

        generateNow();

        List<JsonNode> alerts = notifications("NEW_DEBT_BEFORE_FINANCING");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("params").get("installments").asString()).isEqualTo("4");
        JsonNode preview = body(mockMvc.perform(post("/v1/cards/" + cardA + "/purchase-preview",
                "{\"amount\":\"100.00\",\"installments\":2,\"date\":\"2026-10-15\"}")).andExpect(status().isOk()).andReturn());
        assertThat(preview.get("warnings").toString()).contains("FINANCING_PENDING");
    }

    @Test
    @DisplayName("Pix no crédito registrado hoje: CARD_CASH_OUT_COST uma vez, com taxa efetiva mensal (469 bps) e valor da parcela (280,00)")
    void custoDoPixNoCredito() throws Exception {
        createdBody(idem(post("/v1/card-cash-outs", "{\"cardId\":\"" + cardA + "\",\"accountId\":\"" + accountA + "\",\"principal\":\"1000.00\",\"installments\":4,"
                + "\"totalWithInterest\":\"1120.00\",\"firstInvoiceDate\":\"2026-10-10\"}")));

        generateNow();
        generateNow();

        List<JsonNode> alerts = notifications("CARD_CASH_OUT_COST");
        assertThat(alerts).hasSize(1);
        assertThat(Long.parseLong(alerts.get(0).get("params").get("monthlyRateBps").asString())).isBetween(468L, 470L);
        assertThat(alerts.get(0).get("params").get("installmentAmount").asString()).isEqualTo("280.00");
    }

    @Test
    @DisplayName("assinaturas: duas ativas no mesmo grupo geram SUBSCRIPTION_OVERLAP; semanal criada hoje gera WEEKLY_CHARGE uma vez; SUBSCRIPTION_UNUSED só dispara com subscriptionUnusedDays definido")
    void assinaturas() throws Exception {
        String base = "\"type\":\"CARD_PURCHASE\",\"description\":\"%s\",\"categoryId\":\"" + catFood + "\",\"cardId\":\"" + cardA + "\",\"startDate\":\"2026-11-05\",\"isSubscription\":true";
        createdBody(post("/v1/recurrences", "{" + String.format(base, "Streaming A") + ",\"amount\":\"30.00\",\"frequency\":\"MONTHLY\",\"overlapGroup\":\"video\","
                + "\"lastUsedOn\":\"2026-08-01\"}"));
        createdBody(post("/v1/recurrences", "{" + String.format(base, "Streaming B") + ",\"amount\":\"20.00\",\"frequency\":\"MONTHLY\",\"overlapGroup\":\"Video\"}"));
        createdBody(post("/v1/recurrences", "{" + String.format(base, "Servico semanal") + ",\"amount\":\"5.30\",\"frequency\":\"WEEKLY\"}"));

        generateNow();
        generateNow();

        assertThat(notifications("SUBSCRIPTION_OVERLAP")).hasSize(1);
        assertThat(notifications("WEEKLY_CHARGE")).hasSize(1);
        assertThat(notifications("SUBSCRIPTION_UNUSED")).as("sem N configurado o alerta fica inativo (P-12)").isEmpty();

        saveAlertSettings("{\"subscriptionUnusedDays\":30,\"invoiceDueLeadDays\":3,\"documentDivergenceTolerance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"}}");
        advanceTo(Instant.parse("2026-10-16T12:00:00Z"));
        generateNow();

        List<JsonNode> unused = notifications("SUBSCRIPTION_UNUSED");
        assertThat(unused).hasSize(1);
        assertThat(unused.get(0).get("params").get("name").asString()).isEqualTo("Streaming A");
        assertThat(notifications("WEEKLY_CHARGE")).as("semanal só no dia da criação").hasSize(1);
    }

    @Test
    @DisplayName("reembolso pendente com a fatura vencida: THIRD_PARTY_OVERDUE com a pessoa e o valor; antes do vencimento e depois do recebimento total, nenhum")
    void reembolsoAtrasado() throws Exception {
        String tx = created(sharedPurchase(cardA, "100.00", 1, "2026-10-05", "BPS", "[" + share(null, 5000) + "," + share(ana, 5000) + "]")).get("id").asString();
        advanceTo(Instant.parse("2026-11-05T12:00:00Z"));
        generateNow();
        assertThat(notifications("THIRD_PARTY_OVERDUE")).as("no dia do vencimento ainda não está atrasado").isEmpty();

        advanceTo(Instant.parse("2026-11-06T12:00:00Z"));
        generateNow();

        List<JsonNode> alerts = notifications("THIRD_PARTY_OVERDUE");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("params").get("personName").asString()).isEqualTo("Ana");
        assertThat(alerts.get(0).get("params").get("amount").asString()).isEqualTo("50.00");
        receive(ana, tx, "50.00", accountA, 201);
        advanceTo(Instant.parse("2026-11-07T12:00:00Z"));
        generateNow();
        assertThat(notifications("THIRD_PARTY_OVERDUE")).hasSize(1);
    }

    @Test
    @DisplayName("saldo CASH menor que a próxima obrigação minha cota do imóvel em até 30 dias: BALANCE_BELOW_REAL_ESTATE; com saldo suficiente, nenhum")
    void saldoAbaixoDoImovel() throws Exception {
        createdBody(post("/v1/commitments", "{\"kind\":\"LONG_TERM_PROJECT\",\"name\":\"Casa Exemplo\",\"total\":\"3000.00\",\"schedule\":["
                + "{\"label\":\"Parcela 1\",\"type\":\"INSTALLMENT\",\"dueDate\":\"2026-11-05\",\"amount\":\"3000.00\",\"paid\":false,\"shares\":["
                + amountShare(null, "2000.00") + "," + amountShare(beto, "1000.00") + "]}]}"));
        generateNow();
        assertThat(notifications("BALANCE_BELOW_REAL_ESTATE")).as("saldo de 10.000,00 cobre a cota de 2.000,00").isEmpty();

        created("{\"type\":\"EXPENSE\",\"amount\":\"9000.00\",\"date\":\"2026-10-15\",\"description\":\"Gasto grande\",\"categoryId\":\"" + catFood + "\",\"accountId\":\""
                + accountA + "\",\"method\":\"PIX\"}");
        advanceTo(Instant.parse("2026-10-16T12:00:00Z"));
        generateNow();

        List<JsonNode> alerts = notifications("BALANCE_BELOW_REAL_ESTATE");
        assertThat(alerts).hasSize(1);
        assertThat(alerts.get(0).get("params").get("due").asString()).isEqualTo("2000.00");
    }

    @Test
    @DisplayName("alerta desligado nas configurações não é gerado; notificações são isoladas por usuário e exigem token")
    void desligarEIsolar() throws Exception {
        created(sharedPurchase(cardA, "600.00", 3, "2026-10-05", null, null));
        ObjectNode settings = (ObjectNode) okBody(get("/v1/settings/notifications"));
        ((ObjectNode) settings.get("kinds")).put("NEGATIVE_MONTH_PROJECTED", false);
        mockMvc.perform(put("/v1/settings/notifications", settings.toString())).andExpect(status().isOk());

        generateNow();

        assertThat(notifications("NEGATIVE_MONTH_PROJECTED")).isEmpty();
        String other = otherUserBearer();
        assertThat(body(mockMvc.perform(getAs(other, "/v1/notifications?status=ALL")).andExpect(status().isOk()).andReturn()).get("items")).isEmpty();
        mockMvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get("/v1/notifications")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("PUT das configurações: subscriptionUnusedDays ausente preserva o valor, null limpa e número troca")
    void ausenteNaoLimpaENullLimpa() throws Exception {
        saveAlertSettings("{\"subscriptionUnusedDays\":30,\"invoiceDueLeadDays\":3,\"documentDivergenceTolerance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"}}");
        assertThat(okBody(get("/v1/settings/notifications")).get("alerts").get("subscriptionUnusedDays").asInt()).isEqualTo(30);

        saveAlertSettings("{\"invoiceDueLeadDays\":4,\"documentDivergenceTolerance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"}}");
        JsonNode omitted = okBody(get("/v1/settings/notifications")).get("alerts");
        assertThat(omitted.get("subscriptionUnusedDays").asInt()).as("ausente preserva").isEqualTo(30);
        assertThat(omitted.get("invoiceDueLeadDays").asInt()).isEqualTo(4);

        saveAlertSettings("{\"subscriptionUnusedDays\":45,\"invoiceDueLeadDays\":4,\"documentDivergenceTolerance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"}}");
        assertThat(okBody(get("/v1/settings/notifications")).get("alerts").get("subscriptionUnusedDays").asInt()).isEqualTo(45);

        saveAlertSettings("{\"subscriptionUnusedDays\":null,\"invoiceDueLeadDays\":4,\"documentDivergenceTolerance\":{\"amount\":\"0.00\",\"currency\":\"BRL\"}}");
        assertThat(okBody(get("/v1/settings/notifications")).get("alerts").get("subscriptionUnusedDays").isNull()).as("null limpa").isTrue();
    }

    private void documentAmount(String invoiceId, String json) throws Exception {
        mockMvc.perform(put("/v1/invoices/" + invoiceId + "/document-amount", json)).andExpect(status().isOk());
    }

    @Test
    @DisplayName("BUG-11: documento da fatura diferente do total calculado gera DOCUMENT_DIVERGENCE uma vez por dia, respeita a tolerância e para ao limpar ou igualar")
    void divergenciaDeDocumentoDaFatura() throws Exception {
        created(sharedPurchase(cardA, "100.00", 1, "2026-10-10", null, null));
        String invoiceId = com.moneylog.core.card.domain.InvoiceIds.of(java.util.UUID.fromString(cardA), java.time.LocalDate.of(2026, 10, 25)).toString();
        advanceTo(Instant.parse("2026-10-20T12:00:00Z"));
        disableKind("NEGATIVE_MONTH_PROJECTED");

        generateNow();
        assertThat(notifications("DOCUMENT_DIVERGENCE")).as("sem valor do documento").isEmpty();

        documentAmount(invoiceId, "{\"documentAmount\":\"90.00\"}");
        generateNow();
        generateNow();
        List<JsonNode> alerts = notifications("DOCUMENT_DIVERGENCE");
        assertThat(alerts).as("uma vez no dia").hasSize(1);
        assertThat(alerts.get(0).get("params").get("difference").asString()).isEqualTo("10.00");
        assertThat(alerts.get(0).get("params").get("informed").asString()).isEqualTo("100.00");
        assertThat(alerts.get(0).get("params").get("documented").asString()).isEqualTo("90.00");
        assertThat(alerts.get(0).get("ref").get("type").asString()).isEqualTo("INVOICE");
        assertThat(alerts.get(0).get("ref").get("id").asString()).isEqualTo(invoiceId);

        saveAlertSettings("{\"subscriptionUnusedDays\":null,\"invoiceDueLeadDays\":3,\"documentDivergenceTolerance\":{\"amount\":\"10.00\",\"currency\":\"BRL\"}}");
        advanceTo(Instant.parse("2026-10-21T12:00:00Z"));
        generateNow();
        assertThat(notifications("DOCUMENT_DIVERGENCE")).as("diferença igual à tolerância não avisa").hasSize(1);

        saveAlertSettings("{\"subscriptionUnusedDays\":null,\"invoiceDueLeadDays\":3,\"documentDivergenceTolerance\":{\"amount\":\"9.99\",\"currency\":\"BRL\"}}");
        advanceTo(Instant.parse("2026-10-22T12:00:00Z"));
        generateNow();
        assertThat(notifications("DOCUMENT_DIVERGENCE")).as("1 centavo acima da tolerância avisa de novo no dia seguinte").hasSize(2);

        documentAmount(invoiceId, "{\"documentAmount\":null}");
        advanceTo(Instant.parse("2026-10-23T12:00:00Z"));
        generateNow();
        assertThat(notifications("DOCUMENT_DIVERGENCE")).as("valor removido: nada novo").hasSize(2);

        documentAmount(invoiceId, "{\"documentAmount\":\"100.00\"}");
        advanceTo(Instant.parse("2026-10-24T12:00:00Z"));
        generateNow();
        assertThat(notifications("DOCUMENT_DIVERGENCE")).as("valor igual ao total: nada novo").hasSize(2);
    }
}
