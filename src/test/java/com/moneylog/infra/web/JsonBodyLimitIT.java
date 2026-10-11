package com.moneylog.infra.web;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.MockMvc;

import com.moneylog.util.IntegrationTest;

@IntegrationTest
@DisplayName("S-09: limite de 1 MB no corpo JSON")
class JsonBodyLimitIT {

    @Autowired
    private MockMvc mockMvc;

    @Test
    @DisplayName("JSON de 2 MB em rota anônima: 413 PAYLOAD_TOO_LARGE")
    void deveRecusarJsonGrande() throws Exception {
        String padding = "a".repeat(2 * 1024 * 1024);
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"password\":\"" + padding + "\"}"))
                .andExpect(status().isContentTooLarge()).andExpect(jsonPath("$.code").value("PAYLOAD_TOO_LARGE"));
    }

    @Test
    @DisplayName("JSON pequeno segue o fluxo normal")
    void devePassarJsonPequeno() throws Exception {
        mockMvc.perform(post("/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                .content("{\"email\":\"a@example.com\",\"password\":\"errada\"}")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("multipart de 2 MB não é cortado pelo filtro: chega à segurança e recebe 401, não 413")
    void naoDeveAfetarMultipart() throws Exception {
        mockMvc.perform(multipart("/v1/imports").file(new MockMultipartFile("file", "a.csv", "text/csv", new byte[2 * 1024 * 1024])))
                .andExpect(status().isUnauthorized());
    }
}
