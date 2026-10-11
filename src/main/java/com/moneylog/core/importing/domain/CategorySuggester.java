package com.moneylog.core.importing.domain;

import java.util.ArrayList;
import java.util.Collection;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class CategorySuggester {

    public enum Confidence { HIGH, MEDIUM }

    public record Suggestion(UUID categoryId, String method, Confidence confidence) {
    }

    public record HistoryEntry(ImportDirection direction, String normalizedDescription, UUID categoryId, String method) {
    }

    public record Rule(ImportDirection direction, String normalizedDescription, UUID categoryId, String method) {
    }

    private final Map<String, List<HistoryEntry>> byDescription = new HashMap<>();
    private final Map<String, Rule> rules = new HashMap<>();

    public CategorySuggester(Collection<HistoryEntry> history, Collection<Rule> learned) {
        for (HistoryEntry entry : history) {
            if (entry.normalizedDescription() != null && !entry.normalizedDescription().isBlank() && entry.categoryId() != null) {
                byDescription.computeIfAbsent(key(entry.direction(), entry.normalizedDescription()), k -> new ArrayList<>()).add(entry);
            }
        }
        for (Rule rule : learned) {
            rules.put(key(rule.direction(), rule.normalizedDescription()), rule);
        }
    }

    private static String key(ImportDirection direction, String normalized) {
        return direction + "|" + normalized;
    }

    public Optional<Suggestion> suggest(ImportDirection direction, String normalizedDescription) {
        if (normalizedDescription == null || normalizedDescription.isBlank()) {
            return Optional.empty();
        }
        Rule rule = rules.get(key(direction, normalizedDescription));
        if (rule != null) {
            return Optional.of(new Suggestion(rule.categoryId(), rule.method(), Confidence.HIGH));
        }
        List<HistoryEntry> entries = byDescription.get(key(direction, normalizedDescription));
        if (entries == null || entries.size() < 2) {
            return Optional.empty();
        }
        Map<UUID, Integer> counts = new HashMap<>();
        for (HistoryEntry entry : entries) {
            counts.merge(entry.categoryId(), 1, Integer::sum);
        }
        UUID best = counts.entrySet().stream()
                .max(Map.Entry.<UUID, Integer>comparingByValue().thenComparing(Map.Entry.<UUID, Integer>comparingByKey().reversed()))
                .orElseThrow().getKey();
        int bestCount = counts.get(best);
        if (bestCount < 2) {
            return Optional.empty();
        }
        Confidence confidence = entries.size() >= 3 && bestCount * 5 >= entries.size() * 4 ? Confidence.HIGH : Confidence.MEDIUM;
        Map<String, Integer> methods = new HashMap<>();
        entries.stream().filter(e -> e.categoryId().equals(best) && e.method() != null).forEach(e -> methods.merge(e.method(), 1, Integer::sum));
        String method = methods.entrySet().stream()
                .max(Map.Entry.<String, Integer>comparingByValue().thenComparing(Map.Entry.<String, Integer>comparingByKey().reversed()))
                .map(Map.Entry::getKey).orElse(null);
        return Optional.of(new Suggestion(best, method, confidence));
    }
}
