package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.stereotype.Controller;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.ResponseBody;

import com.moneylog.infra.exception.GlobalExceptionHandler;
import com.moneylog.util.ContractValidation;

@DisplayName("Contrato: UpcomingItem com bucket ausente/nulo e refId nulo")
class UpcomingContractTest {

    private static final String BEARER = "Bearer qualquer-coisa";
    private static final String CARD = "7c2f6b0e-35c0-4a3d-93f1-2f1f7d9b7a10";

    private MockMvc mockMvc;

    @BeforeEach
    void setUp() {
        mockMvc = MockMvcBuilders.standaloneSetup(new UpcomingSample()).setControllerAdvice(new GlobalExceptionHandler())
                .setMessageConverters(new JacksonJsonHttpMessageConverter()).addFilters(new TraceIdFilter()).build();
    }

    private void perform(String sampleCase) throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.get("/v1/dashboard/upcoming").header(HttpHeaders.AUTHORIZATION, BEARER)
                .header("X-Sample-Case", sampleCase)).andExpect(status().isOk()).andExpect(ContractValidation.conforms());
    }

    @Test
    @DisplayName("item datado com bucket, e item sem data com bucket nulo, ausente ou refId nulo, são válidos")
    void deveAceitarBucketNuloEAusente() {
        assertThatCode(() -> {
            perform("dated");
            perform("undated-null-bucket");
            perform("undated-absent-bucket");
            perform("undated-null-ref");
        }).doesNotThrowAnyException();
    }

    @Test
    @DisplayName("bucket com valor fora do enum continua inválido")
    void deveReprovarBucketInvalido() {
        assertThatThrownBy(() -> perform("bad-bucket")).hasMessageContaining("bucket");
    }

    private static Map<String, Object> money(String amount) {
        return Map.of("amount", amount, "currency", "BRL");
    }

    private static Map<String, Object> item(String sampleCase) {
        Map<String, Object> item = new LinkedHashMap<>();
        boolean dated = sampleCase.equals("dated") || sampleCase.equals("bad-bucket");
        item.put("kind", dated ? "INVOICE" : "DEBT_PAYABLE");
        switch (sampleCase) {
            case "dated" -> item.put("bucket", "NEXT_7_DAYS");
            case "undated-null-bucket" -> item.put("bucket", null);
            case "bad-bucket" -> item.put("bucket", "SOMEDAY");
            default -> { }
        }
        item.put("dueDate", dated ? "2026-11-05" : null);
        item.put("direction", "OUT");
        item.put("amount", money("200.00"));
        item.put("label", dated ? "Roxinho" : "Pedro");
        item.put("refType", dated ? "INVOICE" : "DEBT");
        item.put("refId", sampleCase.equals("undated-null-ref") ? null : CARD);
        item.put("items", dated ? List.of(Map.of("kind", "INSTALLMENT", "label", "Notebook 1/6", "installment", Map.of("number", 1, "total", 6),
                "amount", money("200.00"), "informational", true)) : List.of());
        return item;
    }

    @Controller
    @RequestMapping("/v1/dashboard/upcoming")
    @ResponseBody
    static class UpcomingSample {

        @GetMapping
        Map<String, Object> upcoming(@RequestHeader(value = "X-Sample-Case", defaultValue = "dated") String sampleCase) {
            boolean dated = sampleCase.equals("dated") || sampleCase.equals("bad-bucket");
            Map<String, Object> body = new LinkedHashMap<>();
            body.put("items", dated ? List.of(item(sampleCase)) : List.of());
            body.put("undated", dated ? List.of() : List.of(item(sampleCase)));
            body.put("totals", Map.of("out", money("200.00"), "in", money("0.00")));
            return body;
        }
    }
}
