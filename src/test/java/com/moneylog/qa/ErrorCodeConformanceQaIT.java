package com.moneylog.qa;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;
import java.util.TreeSet;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpMethod;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.util.ContractValidation;
import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;
import com.moneylog.util.MutableClockConfig;

/**
 * T43 (QA), critério 7: todo {@code code} de erro (4xx) que a API devolve numa bateria de cenários precisa estar na
 * descrição do contrato (tabela "Códigos de erro"), e toda resposta de erro valida contra o schema.
 */
@IntegrationTest
@Import(MutableClockConfig.class)
@DisplayName("QA T43: códigos de erro × contrato")
class ErrorCodeConformanceQaIT {

    @Autowired
    private MockMvc mvc;
    @Autowired
    private ObjectMapper om;
    @Autowired
    private JdbcTemplate jdbc;
    @Autowired
    private ItAuth auth;
    @Autowired
    private PlatformTransactionManager tm;

    private QaWorld world;
    private QaWorld.Actor a;
    private QaWorld.Actor b;

    @BeforeEach
    void cenario() throws Exception {
        world = new QaWorld(mvc, om, jdbc, auth, tm);
        a = world.new Actor("err").withExpense();
        b = world.new Actor("err-b").withExpense();
    }

    private static Set<String> documentedCodes() throws Exception {
        String yaml = java.nio.file.Files.readString(ContractValidation.SPEC_FILE);
        String table = yaml.substring(yaml.indexOf("## Códigos de erro"), yaml.indexOf("Se o domínio usar outro nome"));
        Set<String> codes = new TreeSet<>();
        Matcher m = Pattern.compile("`([A-Z][A-Z0-9_*]+)`").matcher(table);
        while (m.find()) {
            codes.add(m.group(1));
        }
        return codes;
    }

    private static boolean documented(String code, Set<String> documented) {
        if (documented.contains(code)) {
            return true;
        }
        return documented.stream().filter(d -> d.startsWith("*")).anyMatch(d -> code.endsWith(d.substring(1)));
    }

    private final Map<String, String> seen = new TreeMap<>();
    private final List<String> violations = new ArrayList<>();

    private void probe(String label, HttpMethod m, String url, String body, QaWorld.Actor who) throws Exception {
        MockHttpServletRequestBuilder r = who.req(m, url, body);
        if (m == HttpMethod.POST && url.equals("/v1/transactions")) {
            r.header("Idempotency-Key", UUID.randomUUID().toString());
        }
        MvcResult res = mvc.perform(r).andReturn();
        int s = res.getResponse().getStatus();
        String content = res.getResponse().getContentAsString();
        if (s < 400) {
            seen.put(label + " -> " + s, "(sucesso)");
            return;
        }
        String code = om.readTree(content).path("code").asString();
        seen.put(label, s + " " + code);
        try {
            ContractValidation.responseOnly().match(res);
        } catch (AssertionError | Exception e) {
            violations.add(label + ": resposta " + s + " fora do schema: " + e.getMessage().lines().findFirst().orElse(""));
        }
        JsonNode errors = om.readTree(content).path("errors");
        if (s == 400 && !"VALIDATION_FAILED".equals(code)) {
            violations.add(label + ": 400 com code " + code);
        }
        if (errors.isArray()) {
            for (JsonNode e : errors) {
                seen.put(label + " errors[].code", e.path("code").asString());
            }
        }
    }

    private String acct(String name, String type, String amount, String date) {
        return "{\"name\":\"" + name + "\",\"type\":\"" + type + "\",\"openingBalance\":{\"amount\":\"" + amount + "\",\"currency\":\"BRL\"},\"openingDate\":\"" + date + "\"}";
    }

    @Test
    @DisplayName("BUG-QA-09: moeda diferente de BRL: 422 ACCOUNT_CURRENCY_NOT_SUPPORTED, como o contrato lista (Money.currency aceita só BRL, mas o 422 de domínio é o documentado)")
    void moedaForaDoContrato() throws Exception {
        probe("conta moeda USD", HttpMethod.POST, "/v1/accounts", acct("ok", "CHECKING", "1.00", "2026-10-01").replace("BRL", "USD"), a);
        String v = seen.get("conta moeda USD");
        assertThat(v).as("o contrato lista ACCOUNT_CURRENCY_NOT_SUPPORTED entre os 422 de domínio").startsWith("422");
        assertThat(v).endsWith("ACCOUNT_CURRENCY_NOT_SUPPORTED");
        assertThat(documented(v.substring(v.indexOf(' ') + 1), documentedCodes())).isTrue();
    }

    @Test
    @DisplayName("BUG-QA-12: errors[].code de q longo demais deve ser SIZE")
    void codigoDeValidacaoDeQuery() throws Exception {
        MvcResult res = mvc.perform(a.req(HttpMethod.GET, "/v1/transactions?q=" + "x".repeat(300), null)).andReturn();
        assertThat(om.readTree(res.getResponse().getContentAsString()).path("errors").get(0).path("code").asString()).isEqualTo("SIZE");
    }

    @Test
    @DisplayName("OBS-01 (caracterização): reembolso em mês diferente do da despesa deixa o consumo da categoria negativo no mês do reembolso")
    void reembolsoEmOutroMesDeixaCategoriaNegativa() throws Exception {
        a.create(QaWorld.refund(a.nubank, a.food, a.expenseTx, "30.00", "2026-11-05"));
        JsonNode nov = om.readTree(mvc.perform(a.req(HttpMethod.GET, "/v1/entries?competence=2026-11&lens=CONSUMPTION", null)).andReturn().getResponse().getContentAsString());
        System.out.println("QA-OBS01 novembro=" + nov.get("totals"));
        assertThat(nov.get("totals").get("total").get("amount").asString()).isEqualTo("-30.00");
    }

    @Test
    @DisplayName("bateria de cenários de erro: todo code visto está documentado e toda resposta valida")
    void todoCodeDeErroEstaNoContrato() throws Exception {
        String ex = a.expenseTx;
        // contas
        probe("conta nome só espaços", HttpMethod.POST, "/v1/accounts", acct("   ", "CHECKING", "1.00", "2026-10-01"), a);
        probe("conta nome 101", HttpMethod.POST, "/v1/accounts", acct("n".repeat(101), "CHECKING", "1.00", "2026-10-01"), a);
        probe("conta instituição 101", HttpMethod.POST, "/v1/accounts", acct("ok", "CHECKING", "1.00", "2026-10-01").replace("\"type\"", "\"institution\":\"" + "i".repeat(101) + "\",\"type\""), a);
        probe("conta saldo acima do teto", HttpMethod.POST, "/v1/accounts", acct("ok", "CHECKING", "99999999999999.99", "2026-10-01"), a);
        probe("conta saldo 3 casas", HttpMethod.POST, "/v1/accounts", acct("ok", "CHECKING", "1.005", "2026-10-01"), a);
        probe("conta saldo negativo no teto", HttpMethod.POST, "/v1/accounts", acct("ok", "CHECKING", "-9999999999999.99", "2026-10-01"), a);
        probe("conta tipo CARD", HttpMethod.POST, "/v1/accounts", acct("ok", "CREDIT", "1.00", "2026-10-01"), a);
        probe("arquivar conta com saldo", HttpMethod.POST, "/v1/accounts/" + a.nubank + "/archive", "", a);
        probe("desarquivar ativa", HttpMethod.POST, "/v1/accounts/" + a.xp + "/unarchive", "", a);
        probe("editar saldo inicial com movimentação", HttpMethod.PUT, "/v1/accounts/" + a.nubank, acct("Nubank", "CHECKING", "5.00", "2026-10-01").replace("}", ",\"version\":0}"), a);
        probe("editar conta versão velha", HttpMethod.PUT, "/v1/accounts/" + a.xp, acct("XP", "CHECKING", "0.00", "2026-10-01").replace("}", ",\"version\":9}"), a);
        // categorias
        probe("categoria nome vazio", HttpMethod.POST, "/v1/categories", "{\"name\":\" \",\"kind\":\"EXPENSE\"}", a);
        probe("categoria repetida", HttpMethod.POST, "/v1/categories", "{\"name\":\"alimentação\",\"kind\":\"EXPENSE\"}", a);
        probe("categoria nome 61", HttpMethod.POST, "/v1/categories", "{\"name\":\"" + "c".repeat(61) + "\",\"kind\":\"EXPENSE\"}", a);
        probe("categoria desarquivar ativa", HttpMethod.POST, "/v1/categories/" + a.food + "/unarchive", "", a);
        probe("categoria arquivar 2x", HttpMethod.POST, "/v1/categories/" + a.salary + "/archive", "", a);
        probe("categoria arquivar 2x (2)", HttpMethod.POST, "/v1/categories/" + a.salary + "/archive", "", a);
        // movimentações
        probe("valor zero", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "0.00", "2026-10-10", "x"), a);
        probe("valor 13 dígitos", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "9999999999999.99", "2026-10-10", "x"), a);
        probe("valor 12 dígitos", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "999999999999.99", "2026-10-10", "x"), a);
        probe("descrição 201", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "1.00", "2026-10-10", "d".repeat(201)), a);
        probe("despesa categoria de receita", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.salary, "1.00", "2026-10-10", "x"), a);
        probe("receita categoria de despesa", HttpMethod.POST, "/v1/transactions", QaWorld.income(a.nubank, a.food, "1.00", "2026-10-10"), a);
        probe("despesa em conta de investimento", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.xpInvest, a.food, "1.00", "2026-10-10", "x"), a);
        probe("transferência mesma conta", HttpMethod.POST, "/v1/transactions", QaWorld.transfer(a.nubank, a.nubank, "1.00", "2026-10-10"), a);
        probe("transferência caixa→invest", HttpMethod.POST, "/v1/transactions", QaWorld.transfer(a.nubank, a.xpInvest, "1.00", "2026-10-10"), a);
        probe("investimento caixa→caixa", HttpMethod.POST, "/v1/transactions", QaWorld.investment(a.nubank, a.xp, "CONTRIBUTION", "1.00", "2026-10-10"), a);
        probe("ajuste sem motivo", HttpMethod.POST, "/v1/transactions", QaWorld.adjustment(a.nubank, "INCREASE", "1.00", "2026-10-10").replace("conferência", ""), a);
        probe("ajuste YIELD em conta caixa", HttpMethod.POST, "/v1/transactions", QaWorld.adjustment(a.nubank, "INCREASE", "1.00", "2026-10-10").replace("\"direction\"", "\"kind\":\"YIELD\",\"direction\""), a);
        probe("reembolso acima da original", HttpMethod.POST, "/v1/transactions", QaWorld.refund(a.nubank, a.food, ex, "100.01", "2026-10-10"), a);
        probe("reembolso sem original", HttpMethod.POST, "/v1/transactions", QaWorld.refund(a.nubank, a.food, ex, "1.00", "2026-10-10").replace(",\"refundOfTransactionId\":\"" + ex + "\"", ""), a);
        probe("reembolso de receita", HttpMethod.POST, "/v1/transactions", QaWorld.refund(a.nubank, a.food, a.create(QaWorld.income(a.nubank, b.salary.isEmpty() ? a.salary : a.category("Extra", com.moneylog.core.category.domain.CategoryKind.INCOME), "5.00", "2026-10-10")).get("id").asString(), "1.00", "2026-10-10"), a);
        probe("reembolso com cardId", HttpMethod.POST, "/v1/transactions", QaWorld.refund(a.nubank, a.food, ex, "1.00", "2026-10-10").replace("}", ",\"cardId\":\"" + UUID.randomUUID() + "\"}"), a);
        probe("reembolso categoria diferente", HttpMethod.POST, "/v1/transactions", QaWorld.refund(a.nubank, a.category("Lazer", com.moneylog.core.category.domain.CategoryKind.EXPENSE), ex, "1.00", "2026-10-10"), a);
        probe("tipo CARD_PURCHASE", HttpMethod.POST, "/v1/transactions", "{\"type\":\"CARD_PURCHASE\"}", a);
        probe("tipo LOAN_GIVEN", HttpMethod.POST, "/v1/transactions", "{\"type\":\"LOAN_GIVEN\",\"amount\":\"1.00\"}", a);
        probe("categoria inexistente", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, UUID.randomUUID().toString(), "1.00", "2026-10-10", "x"), a);
        probe("sem Idempotency-Key", HttpMethod.POST, "/v1/transactions", "{}", a); // ainda 400
        probe("outro usuário", HttpMethod.GET, "/v1/transactions/" + b.expenseTx, null, a);
        probe("editar tipo diferente", HttpMethod.PUT, "/v1/transactions/" + ex, QaWorld.income(a.nubank, a.salary, "1.00", "2026-10-10").replace("}", ",\"version\":0}"), a);
        probe("editar sem version", HttpMethod.PUT, "/v1/transactions/" + ex, QaWorld.expense(a.nubank, a.food, "1.00", "2026-10-10", "x"), a);
        probe("editar version velha", HttpMethod.PUT, "/v1/transactions/" + ex, QaWorld.expense(a.nubank, a.food, "1.00", "2026-10-10", "x").replace("}", ",\"version\":7}"), a);
        probe("estorno sem motivo", HttpMethod.POST, "/v1/transactions/" + ex + "/void", "{\"reason\":\"\"}", a);
        probe("estorno motivo 501", HttpMethod.POST, "/v1/transactions/" + ex + "/void", "{\"reason\":\"" + "r".repeat(501) + "\"}", a);
        // reembolso ativo trava a despesa
        String refund = a.create(QaWorld.refund(a.nubank, a.food, ex, "10.00", "2026-10-10")).get("id").asString();
        probe("estornar original com reembolso", HttpMethod.POST, "/v1/transactions/" + ex + "/void", "{\"reason\":\"x\"}", a);
        probe("trocar categoria com reembolso", HttpMethod.PUT, "/v1/transactions/" + ex,
                QaWorld.expense(a.nubank, a.category("Outra", com.moneylog.core.category.domain.CategoryKind.EXPENSE), "100.00", "2026-10-10", "x").replace("}", ",\"version\":0}"), a);
        probe("baixar valor abaixo de Σ reembolsos", HttpMethod.PUT, "/v1/transactions/" + ex, QaWorld.expense(a.nubank, a.food, "5.00", "2026-10-10", "x").replace("}", ",\"version\":0}"), a);
        probe("estornar 1x", HttpMethod.POST, "/v1/transactions/" + refund + "/void", "{\"reason\":\"x\"}", a);
        probe("estornar 2x", HttpMethod.POST, "/v1/transactions/" + refund + "/void", "{\"reason\":\"x\"}", a);
        probe("editar estornada", HttpMethod.PUT, "/v1/transactions/" + refund, QaWorld.refund(a.nubank, a.food, ex, "1.00", "2026-10-10").replace("}", ",\"version\":1}"), a);
        // conta arquivada recebe movimentação
        String parked = a.account("Parada", "WALLET", "0.00");
        probe("arquivar conta zerada", HttpMethod.POST, "/v1/accounts/" + parked + "/archive", "", a);
        probe("despesa em conta arquivada", HttpMethod.POST, "/v1/transactions", QaWorld.expense(parked, a.food, "1.00", "2026-10-10", "x"), a);
        probe("arquivar categoria", HttpMethod.POST, "/v1/categories/" + a.food + "/archive", "", a);
        probe("despesa em categoria arquivada", HttpMethod.POST, "/v1/transactions", QaWorld.expense(a.nubank, a.food, "1.00", "2026-10-10", "x"), a);
        // fora do app
        probe("rota inexistente", HttpMethod.GET, "/v1/nada", null, a);
        probe("método não permitido", HttpMethod.DELETE, "/v1/accounts/" + a.nubank, null, a);
        probe("Accept inaceitável", HttpMethod.GET, "/v1/accounts", null, a);

        // mídia errada
        MvcResult media = mvc.perform(a.req(HttpMethod.POST, "/v1/accounts", "x=1").contentType("application/x-www-form-urlencoded")).andReturn();
        seen.put("Content-Type form", media.getResponse().getStatus() + " " + om.readTree(media.getResponse().getContentAsString()).path("code").asString());
        MvcResult notAcceptable = mvc.perform(a.req(HttpMethod.GET, "/v1/accounts", null).accept("application/xml")).andReturn();
        seen.put("Accept xml", notAcceptable.getResponse().getStatus() + " " + notAcceptable.getResponse().getContentAsString().replaceAll(".*\"code\":\"([A-Z_]*)\".*", "$1"));

        Set<String> documented = documentedCodes();
        Set<String> undocumented = new TreeSet<>();
        seen.forEach((label, v) -> {
            String code = v.contains(" ") ? v.substring(v.indexOf(' ') + 1) : v;
            if (!label.endsWith("errors[].code") && !v.equals("(sucesso)") && !documented(code, documented)) {
                undocumented.add(code + "  <- " + label + " (" + v + ")");
            }
        });
        seen.forEach((k, v) -> System.out.println("QA-CODE " + k + " => " + v));
        System.out.println("QA-UNDOCUMENTED " + undocumented);
        System.out.println("QA-VIOLATIONS " + violations);
        assertThat(undocumented).as("codes devolvidos pela API e ausentes da descrição do contrato").isEmpty();
        assertThat(violations).isEmpty();
    }
}
