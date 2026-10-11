package com.moneylog.core.search.domain;

import java.util.Optional;

import com.moneylog.core.sk.text.PlainText;

public final class SearchTerm {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;

    private SearchTerm() {
    }

    public static Optional<String> normalize(String raw) {
        if (raw == null || PlainText.hasControlCharacters(raw, false)) {
            return Optional.empty();
        }
        String term = raw.strip();
        int length = term.codePointCount(0, term.length());
        return length < MIN_LENGTH || length > MAX_LENGTH ? Optional.empty() : Optional.of(term);
    }
}
