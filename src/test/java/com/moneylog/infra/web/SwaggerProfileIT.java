package com.moneylog.infra.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.web.servlet.MockMvc;

import com.moneylog.util.IntegrationTest;

@DisplayName("springdoc só no perfil dev")
class SwaggerProfileIT {

    @IntegrationTest
    @ActiveProfiles({"it", "dev"})
    @Nested
    @DisplayName("perfil dev")
    class Dev {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("/v3/api-docs e /swagger-ui.html respondem")
        void deveExporDocumentacao() throws Exception {
            mockMvc.perform(get("/v3/api-docs"))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.openapi").exists());
            mockMvc.perform(get("/swagger-ui.html")).andExpect(status().is3xxRedirection());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isOk());
        }
    }

    @IntegrationTest
    @Nested
    @DisplayName("perfil padrão (sem dev)")
    class Padrao {

        @Autowired
        private MockMvc mockMvc;

        @Test
        @DisplayName("/v3/api-docs e /swagger-ui.html respondem 404")
        void naoDeveExporDocumentacao() throws Exception {
            mockMvc.perform(get("/v3/api-docs")).andExpect(status().isNotFound());
            mockMvc.perform(get("/swagger-ui.html")).andExpect(status().isNotFound());
            mockMvc.perform(get("/swagger-ui/index.html")).andExpect(status().isNotFound());
        }
    }
}
