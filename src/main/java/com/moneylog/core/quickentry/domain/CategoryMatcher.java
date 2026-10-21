package com.moneylog.core.quickentry.domain;

import java.text.Normalizer;
import java.util.List;
import java.util.Locale;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.category.domain.Category;

public final class CategoryMatcher {

    private CategoryMatcher() {
    }

    public static Optional<Category> find(String requested, List<Category> activeOfKind, Map<UUID, String> systemKeysById) {
        if (requested == null || requested.isBlank()) {
            return Optional.empty();
        }
        String asKey = requested.strip().toUpperCase(Locale.ROOT);
        Optional<Category> byKey = activeOfKind.stream()
                .filter(c -> asKey.equals(systemKeysById.get(c.id())))
                .findFirst();
        if (byKey.isPresent()) {
            return byKey;
        }
        String exact = requested.strip().toLowerCase(Locale.ROOT);
        List<Category> sameName = activeOfKind.stream().filter(c -> c.name().strip().toLowerCase(Locale.ROOT).equals(exact)).toList();
        if (sameName.size() == 1) {
            return Optional.of(sameName.get(0));
        }
        String wanted = normalize(requested);
        List<Category> candidates = activeOfKind.stream().filter(c -> normalize(c.name()).equals(wanted)).toList();
        return candidates.size() == 1 ? Optional.of(candidates.get(0)) : Optional.empty();
    }

    static String normalize(String text) {
        String decomposed = Normalizer.normalize(text.strip(), Normalizer.Form.NFD);
        return decomposed.replaceAll("\\p{M}", "").toLowerCase(Locale.ROOT);
    }
}
