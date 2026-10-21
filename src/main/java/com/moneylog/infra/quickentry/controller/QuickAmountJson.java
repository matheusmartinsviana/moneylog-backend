package com.moneylog.infra.quickentry.controller;

import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.ValueDeserializer;

import com.moneylog.core.quickentry.domain.QuickAmountInput;

public final class QuickAmountJson extends ValueDeserializer<QuickAmountInput> {

    @Override
    public QuickAmountInput deserialize(JsonParser parser, DeserializationContext context) {
        if (parser.hasToken(JsonToken.VALUE_STRING)) {
            return new QuickAmountInput.Text(parser.getString());
        }
        if (parser.hasToken(JsonToken.VALUE_NUMBER_INT) || parser.hasToken(JsonToken.VALUE_NUMBER_FLOAT)) {
            return new QuickAmountInput.JsonNumber(parser.getString());
        }
        return (QuickAmountInput) context.handleUnexpectedToken(QuickAmountInput.class, parser);
    }
}
