package com.moneylog.infra.web;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import tools.jackson.core.JacksonException;
import tools.jackson.databind.cfg.EnumFeature;
import tools.jackson.databind.json.JsonMapper;

import com.moneylog.core.goal.domain.GoalPriority;

@DisplayName("StrictDatesJson e enum por texto (BUG-QA-30, S-261)")
class StrictDatesJsonTest {

    record Body(LocalDate date, GoalPriority priority) {
    }

    private final JsonMapper mapper = JsonMapper.builder().addModule(new StrictDatesJson()).enable(EnumFeature.FAIL_ON_NUMBERS_FOR_ENUMS).build();

    @Test
    @DisplayName("aceita só AAAA-MM-DD e o enum pelo nome")
    void deveAceitarOFormatoDoContrato() {
        Body body = mapper.readValue("{\"date\":\"2026-10-10\",\"priority\":\"LOW\"}", Body.class);

        assertThat(body.date()).isEqualTo(LocalDate.of(2026, 10, 10));
        assertThat(body.priority()).isEqualTo(GoalPriority.LOW);
        assertThat(mapper.readValue("{\"date\":null,\"priority\":null}", Body.class).date()).isNull();
    }

    @Test
    @DisplayName("recusa data-hora (com Z, sem fuso, com offset), datas impossíveis, formatos soltos, número e array")
    void deveRecusarDatasForaDoContrato() {
        for (String date : new String[] {"\"2026-10-10T23:30:00Z\"", "\"2026-10-10T10:00:00\"", "\"2026-10-10T10:00:00-03:00\"", "\"2026-02-30\"", "\"20261010\"",
                "\"10/10/2026\"", "\"2026-1-5\"", "\"\"", "\" 2026-10-10\"", "20261010", "[2026,10,10]", "{}", "true"}) {
            assertThatThrownBy(() -> mapper.readValue("{\"date\":" + date + "}", Body.class)).as(date).isInstanceOf(JacksonException.class);
        }
    }

    @Test
    @DisplayName("enum por posição numérica (priority: 1) é recusado")
    void deveRecusarEnumPorPosicao() {
        for (String priority : new String[] {"0", "1", "2", "\"1\""}) {
            assertThatThrownBy(() -> mapper.readValue("{\"priority\":" + priority + "}", Body.class)).as(priority).isInstanceOf(JacksonException.class);
        }
    }
}
