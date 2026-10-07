package com.moneylog.core.importing.parser;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.LocalDate;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

@DisplayName("DateParser")
class DateParserTest {

    @ParameterizedTest
    @ValueSource(strings = {"15/10/2026", "2026-10-15", "15/10/26", "15-10-2026", "15.10.2026", "20261015", "20261015120000[-3:BRT]", "2026-10-15 08:30:00",
            " 15/10/2026 "})
    @DisplayName("formatos aceitos dão 15/10/2026")
    void formatos(String text) {
        assertThat(DateParser.parse(text)).contains(LocalDate.of(2026, 10, 15));
    }

    @ParameterizedTest
    @ValueSource(strings = {"31/02/2026", "2026-02-30", "32/01/2026", "15/13/2026", "abc", "", "2026/10/15", "15/10"})
    @DisplayName("data impossível ou fora de formato vira vazio")
    void invalidas(String text) {
        assertThat(DateParser.parse(text)).isEmpty();
    }
}
