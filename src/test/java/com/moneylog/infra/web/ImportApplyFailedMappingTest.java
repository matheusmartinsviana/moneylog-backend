package com.moneylog.infra.web;

import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.converter.json.JacksonJsonHttpMessageConverter;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.importing.exception.ImportingExceptions.ImportApplyFailedException;
import com.moneylog.infra.exception.GlobalExceptionHandler;

@DisplayName("IMPORT_APPLY_FAILED expõe position e cause estruturados (contrato 2.10.0)")
class ImportApplyFailedMappingTest {

    private final MockMvc mockMvc = MockMvcBuilders.standaloneSetup(new Thrower()).setControllerAdvice(new GlobalExceptionHandler())
            .setMessageConverters(new JacksonJsonHttpMessageConverter()).addFilters(new TraceIdFilter()).build();

    @Test
    @DisplayName("422 com code, position e cause, mantendo o detail")
    void deveExporPositionECause() throws Exception {
        mockMvc.perform(MockMvcRequestBuilders.post("/boom")).andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code").value("IMPORT_APPLY_FAILED"))
                .andExpect(jsonPath("$.position").value(7))
                .andExpect(jsonPath("$.cause").value("IMPORT_LINE_ALREADY_RECORDED"))
                .andExpect(jsonPath("$.detail").value("Linha 7 do lote não pôde ser aplicada (IMPORT_LINE_ALREADY_RECORDED): já existe"));
    }

    @RestController
    static class Thrower {
        @PostMapping("/boom")
        void boom() {
            throw new ImportApplyFailedException(7, "IMPORT_LINE_ALREADY_RECORDED", "já existe");
        }
    }
}
