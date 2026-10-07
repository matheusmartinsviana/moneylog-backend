package com.moneylog.infra.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

/**
 * BUG-QA-30 (S-261): o contrato diz {@code format: date} ({@code AAAA-MM-DD}) para {@code LocalDate}, e o Jackson aceitava data-hora
 * ({@code 2026-10-10T23:30:00Z}, {@code ...T10:00:00}) e gravava só o dia, em silêncio e de forma inconsistente (com fuso {@code -03:00} dava
 * 400). Aqui só {@code ISO_LOCAL_DATE} passa; qualquer outra forma é {@code 400 INVALID_FORMAT}. (Enum por posição numérica é recusado pelo
 * {@code FAIL_ON_NUMBERS_FOR_ENUMS} de {@code application.yaml}.)
 */
public final class StrictDatesJson extends SimpleModule {

    public StrictDatesJson() {
        super("moneylog-strict-dates");
        addDeserializer(LocalDate.class, new StrictLocalDateDeserializer());
    }

    private static final class StrictLocalDateDeserializer extends ValueDeserializer<LocalDate> {
        @Override
        public LocalDate deserialize(JsonParser parser, DeserializationContext context) {
            if (!parser.hasToken(JsonToken.VALUE_STRING)) {
                return (LocalDate) context.handleUnexpectedToken(LocalDate.class, parser);
            }
            String text = parser.getString();
            try {
                return LocalDate.parse(text, DateTimeFormatter.ISO_LOCAL_DATE);
            } catch (DateTimeParseException e) {
                return (LocalDate) context.handleWeirdStringValue(LocalDate.class, text, "a data deve ser AAAA-MM-DD");
            }
        }
    }
}
