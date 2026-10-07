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

/**
 * Resolve o alvo de um wikilink para uma nota ativa pelo <b>nome do arquivo</b> (S-158, como o Obsidian): sem {@code .md}, sem diferenciar
 * caixa nem acento. {@code [[pasta/nome]]} casa com o caminho inteiro ou com o final dele. Empate entre pastas: o <b>caminho mais curto</b>
 * (depois a ordem do caminho dobrado e, entre nomes só equivalentes por acento/caixa, o escrito exatamente igual e a ordem lexicográfica do caminho: o resultado é determinístico, nunca depende de um id aleatório). Sem alvo = {@link Optional#empty()} (link pendente).
 */
public final class WikilinkResolver {

    /** Nota ativa do índice de resolução. */
    public record Entry(UUID id, NotePath path) {
    }

    /** Tamanho máximo da chave (coluna {@code target_name_key}). */
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

    /** Chave de nome de um caminho: o nome do arquivo sem {@code .md}, sem caixa e sem acento. */
    public static String nameKey(NotePath path) {
        return CodePoints.truncate(TextFolding.fold(path.name()), MAX_KEY);
    }

    /**
     * Chave de nome de um alvo escrito num wikilink: o último segmento, sem {@code .md}, sem caixa e sem acento. É a mesma chave que as
     * notas de mesmo nome têm ({@link #nameKey(NotePath)}), e por ela o índice acha os links que um nome novo ou removido afeta.
     */
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
        // determinístico: caminho mais curto; depois a ordem do caminho dobrado; só se o caminho dobrado é o mesmo (Café x Cafe) vale o escrito
        // exatamente igual, depois a ordem lexicográfica do caminho; o id só serve de última chave e nunca decide (os caminhos são únicos)
        Comparator<Indexed> best = Comparator.<Indexed>comparingInt(i -> i.entry().path().value().length())
                .thenComparing(Indexed::foldedFull).thenComparing(i -> !isExact(i.entry().path(), written))
                .thenComparing(i -> i.entry().path().value()).thenComparing(i -> i.entry().id());
        return candidates.stream()
                .filter(i -> slash < 0 || i.foldedFull().equals(folded) || i.foldedFull().endsWith("/" + folded))
                .min(best).map(i -> i.entry().id());
    }

    /** O nome (ou o caminho, se o link foi escrito como caminho) é igual ao escrito, sem dobrar caixa nem acento. */
    private static boolean isExact(NotePath path, String written) {
        return written.indexOf('/') < 0 ? path.name().equals(written)
                : path.withoutExtension().equals(written) || path.withoutExtension().endsWith("/" + written);
    }
}
