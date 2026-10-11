package com.moneylog.infra.web;

import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.module.SimpleModule;

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
