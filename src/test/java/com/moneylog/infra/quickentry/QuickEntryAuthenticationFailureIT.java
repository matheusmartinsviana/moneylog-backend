package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import com.moneylog.core.quickentry.app.gateways.QuickEntryTokenGateway;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;
import com.moneylog.util.IntegrationTest;

@IntegrationTest
@ExtendWith(OutputCaptureExtension.class)
@DisplayName("Autenticação por token com falha interna")
class QuickEntryAuthenticationFailureIT {

    @Autowired
    private MockMvc mvc;
    @MockitoBean
    private QuickEntryTokenGateway tokens;

    @Test
    @DisplayName("falha do banco na busca do token vira 500 genérico, sem o segredo nem a mensagem interna, e o log não traz o token")
    void deveResponder500SemVazar(CapturedOutput output) throws Exception {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
        when(tokens.findByTokenHashForAuthentication(anyString())).thenThrow(new IllegalStateException("conexão com o banco perdida"));

        MvcResult result = mvc.perform(MockMvcRequestBuilders.post("/v1/quick-entries").contentType(MediaType.APPLICATION_JSON).content("{}")
                .header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value())).andReturn();

        String body = result.getResponse().getContentAsString();
        assertThat(result.getResponse().getStatus()).isEqualTo(500);
        assertThat(body).contains("INTERNAL_ERROR").doesNotContain(secret.value(), secret.hash(), "conexão com o banco perdida");
        assertThat(output.getAll()).doesNotContain(secret.value(), secret.hash());
    }
}
