package com.moneylog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationState;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.core.env.Environment;
import org.springframework.test.web.servlet.MockMvc;

import com.moneylog.util.IntegrationTest;
import com.moneylog.util.ItAuth;

@IntegrationTest
@DisplayName("Aplicação sobe com Postgres real")
class ApplicationHealthIT {

    @Autowired
    private MockMvc mockMvc;

    @Autowired
    private Flyway flyway;

    @Autowired
    private Environment environment;

    @Autowired
    private ItAuth auth;

    @Test
    @DisplayName("GET /actuator/health responde 200 com status UP e sem detalhes")
    void deveResponderHealthUp() throws Exception {
        mockMvc.perform(get("/actuator/health"))
                .andExpect(status().isOk())
                .andExpect(content().json("{\"status\":\"UP\"}", org.springframework.test.json.JsonCompareMode.STRICT));
    }

    @Test
    @DisplayName("só o health fica exposto no actuator")
    void deveExporSoOHealth() throws Exception {
        for (String endpoint : new String[] {"/actuator/env", "/actuator/info", "/actuator/beans", "/actuator/configprops"}) {
            mockMvc.perform(get(endpoint).header("Authorization", auth.ownerBearer())).andExpect(status().isNotFound());
        }
    }

    @Test
    @DisplayName("Flyway roda sem migração falha ou pendente")
    void deveRodarFlywaySemErro() {
        assertThat(Arrays.stream(flyway.info().all()).map(m -> m.getState()))
                .noneMatch(state -> state == MigrationState.FAILED || state == MigrationState.PENDING);
        assertThat(flyway.validateWithResult().validationSuccessful).isTrue();
    }

    @Test
    @DisplayName("Hibernate só valida o schema (ddl-auto = validate)")
    void deveUsarDdlAutoValidate() {
        assertThat(environment.getProperty("spring.jpa.hibernate.ddl-auto")).isEqualTo("validate");
    }
}
