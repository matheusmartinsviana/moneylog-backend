package com.moneylog.core.note.domain;

import java.text.Normalizer;
import java.util.ArrayList;
import java.util.Collection;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

public final class WikilinkResolver {

    public record Entry(UUID id, NotePath path) {
    }

    private static final int MAX_KEY = 255;

    private record Indexed(Entry entry, String foldedFull) {
    }

    private final Map<String, List<Indexed>> byName = new HashMap<>();

    public WikilinkResolver(Collection<Entry> activeNotes) {
        for (Entry entry : activeNotes) {
            String name = nameKey(entry.path());
            byName.computeIfAbsent(name, k -> new ArrayList<>()).add(new Indexed(entry, entry.path().foldedWithoutExtension()));
        }
    }

    public static String nameKey(NotePath path) {
        return CodePoints.truncate(TextFolding.fold(path.name()), MAX_KEY);
    }

    public static String nameKey(String target) {
        String folded = TextFolding.fold(Wikilink.withoutExtension(target.strip()).replaceFirst("^/+", "").strip());
        return CodePoints.truncate(folded.substring(folded.lastIndexOf('/') + 1), MAX_KEY);
    }

    public Optional<UUID> resolve(Wikilink link) {
        return resolve(link.target());
    }

    public Optional<UUID> resolve(String target) {
        String written = Normalizer.normalize(Wikilink.withoutExtension(target.strip()).replaceFirst("^/+", "").strip(), Normalizer.Form.NFC);
        String folded = TextFolding.fold(written);
        if (folded.isEmpty()) {
            return Optional.empty();
        }
        int slash = folded.lastIndexOf('/');
        String name = CodePoints.truncate(slash < 0 ? folded : folded.substring(slash + 1), MAX_KEY);
        List<Indexed> candidates = byName.get(name);
        if (candidates == null) {
            return Optional.empty();
        }
        Comparator<Indexed> best = Comparator.<Indexed>comparingInt(i -> i.entry().path().value().length())
                .thenComparing(Indexed::foldedFull).thenComparing(i -> !isExact(i.entry().path(), written))
                .thenComparing(i -> i.entry().path().value()).thenComparing(i -> i.entry().id());
        return candidates.stream()
                .filter(i -> slash < 0 || i.foldedFull().equals(folded) || i.foldedFull().endsWith("/" + folded))
                .min(best).map(i -> i.entry().id());
    }

    private static boolean isExact(NotePath path, String written) {
        return written.indexOf('/') < 0 ? path.name().equals(written)
                : path.withoutExtension().equals(written) || path.withoutExtension().endsWith("/" + written);
    }
}
