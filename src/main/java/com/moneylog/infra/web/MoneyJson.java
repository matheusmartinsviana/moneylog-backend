package com.moneylog.infra.web;

import java.util.Set;

import tools.jackson.core.JsonGenerator;
import tools.jackson.core.JsonParser;
import tools.jackson.core.JsonToken;
import tools.jackson.databind.DeserializationContext;
import tools.jackson.databind.SerializationContext;
import tools.jackson.databind.ValueDeserializer;
import tools.jackson.databind.ValueSerializer;
import tools.jackson.databind.module.SimpleModule;

import com.moneylog.core.sk.exception.DomainException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public final class MoneyJson extends SimpleModule {

    private static final Set<String> FIELDS = Set.of("amount", "currency");

    public MoneyJson() {
        super("moneylog-money");
        addSerializer(Money.class, new MoneySerializer());
        addDeserializer(Money.class, new MoneyDeserializer());
    }

    private static final class MoneySerializer extends ValueSerializer<Money> {
        @Override
        public void serialize(Money value, JsonGenerator gen, SerializationContext context) {
            gen.writeStartObject();
            gen.writeStringProperty("amount", value.toPlainString());
            gen.writeStringProperty("currency", value.currency().value());
            gen.writeEndObject();
        }
    }

    private static final class MoneyDeserializer extends ValueDeserializer<Money> {
        @Override
        public Money deserialize(JsonParser p, DeserializationContext ctxt) {
            if (p.currentToken() != JsonToken.START_OBJECT) {
                return ctxt.reportInputMismatch(Money.class, "Money deve ser um objeto { amount, currency }");
            }
            String amount = null;
            String currency = null;
            while (p.nextToken() == JsonToken.PROPERTY_NAME) {
                String name = p.currentName();
                JsonToken value = p.nextToken();
                if (!FIELDS.contains(name)) {
                    ctxt.handleUnknownProperty(p, this, Money.class, name);
                    p.skipChildren();
                    continue;
                }
                if (value != JsonToken.VALUE_STRING) {
                    return ctxt.reportInputMismatch(Money.class, "'%s' deve ser uma string", name);
                }
                if ("amount".equals(name)) {
                    amount = p.getString();
                } else {
                    currency = p.getString();
                }
            }
            if (amount == null || currency == null) {
                return ctxt.reportInputMismatch(Money.class, "Money exige 'amount' e 'currency'");
            }
            try {
                return Money.of(amount, CurrencyCode.of(currency));
            } catch (DomainException e) {
                return ctxt.reportInputMismatch(Money.class, "Valor monetário inválido: %s", e.code());
            }
        }
    }
}
