package com.moneylog.core.quickentry.domain;

import java.util.Objects;

import com.moneylog.core.sk.money.Money;

public sealed interface QuickAmountInput {

    String raw();

    Money parse();

    record Text(String raw) implements QuickAmountInput {

        @Override
        public Money parse() {
            return AmountText.parseText(raw);
        }
    }

    record JsonNumber(String raw) implements QuickAmountInput {

        public JsonNumber {
            Objects.requireNonNull(raw, "raw");
        }

        @Override
        public Money parse() {
            return AmountText.parseJsonNumber(raw);
        }
    }
}
