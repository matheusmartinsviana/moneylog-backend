package com.moneylog.infra.quickentry;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.test.context.TestPropertySource;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockMvcRequestBuilders;

import tools.jackson.databind.JsonNode;

import com.moneylog.core.quickentry.domain.QuickEntryToken;
import com.moneylog.core.quickentry.domain.QuickEntryTokenSecret;

@TestPropertySource(properties = {
        "moneylog.quick-entry.max-amount=1000.00",
        "moneylog.quick-entry.max-entries-per-day=2"})
@DisplayName("POST /v1/quick-entries com tetos ligados (T6): QUICK_LIMIT_REACHED em problem+json português")
class QuickEntryEndpointLimitsIT extends QuickEntryItBase {

    private MvcResult send(World w, String json) throws Exception {
        QuickEntryTokenSecret secret = QuickEntryTokenSecret.generate();
        Instant now = clock.instant();
        committed(() -> tokens.insert(QuickEntryToken.issue(UUID.randomUUID(), w.userId(), w.accountId(), "iPhone", secret.prefix(), secret.hash(), now,
                now.plusSeconds(3600))));
        return mockMvc.perform(MockMvcRequestBuilders.post("/v1/quick-entries").header(HttpHeaders.AUTHORIZATION, "Bearer " + secret.value())
                .header("Idempotency-Key", "k" + UUID.randomUUID().toString().replace("-", "")).contentType(MediaType.APPLICATION_JSON).content(json))
                .andReturn();
    }

    @Test
    @DisplayName("acima do teto por lançamento e do teto de quantidade: 422 QUICK_LIMIT_REACHED, detail diz qual, nada gravado")
    void deveDevolverOTetoConfigurado() throws Exception {
        World w = world();

        MvcResult above = send(w, "{\"kind\":\"EXPENSE\",\"amount\":\"1.000,01\"}");
        assertThat(above.getResponse().getStatus()).isEqualTo(422);
        JsonNode body = objectMapper.readTree(above.getResponse().getContentAsString());
        assertThat(body.get("code").asString()).isEqualTo("QUICK_LIMIT_REACHED");
        assertThat(body.get("detail").asString()).isEqualTo("O lançamento rápido aceita até R$ 1.000,00 por vez. Lance este valor pelo app.");
        assertThat(count("transactions", w.userId())).isZero();

        assertThat(send(w, "{\"kind\":\"EXPENSE\",\"amount\":\"1.000,00\"}").getResponse().getStatus()).isEqualTo(201);
        assertThat(send(w, "{\"kind\":\"EXPENSE\",\"amount\":\"1\"}").getResponse().getStatus()).isEqualTo(201);
        MvcResult third = send(w, "{\"kind\":\"EXPENSE\",\"amount\":\"1\"}");
        assertThat(third.getResponse().getStatus()).isEqualTo(422);
        assertThat(objectMapper.readTree(third.getResponse().getContentAsString()).get("detail").asString())
                .isEqualTo("Você chegou a 2 lançamentos rápidos hoje. Use o app para lançar mais.");
        assertThat(count("transactions", w.userId())).isEqualTo(2);
    }
}
