package com.moneylog.core.note.domain;

import java.util.ArrayList;
import java.util.List;
import java.util.function.Function;

/**
 * Reescreve o <b>alvo</b> de wikilinks no texto, preservando tudo o mais (alias, seção, embed, código e o resto do corpo, byte a byte).
 * Links dentro de código nunca são tocados (o {@link WikilinkParser} não os devolve).
 */
public final class WikilinkRewriter {

    private WikilinkRewriter() {
    }

    /**
     * @param newTarget devolve o novo alvo de cada link, ou {@code null} para deixá-lo como está
     * @return o texto reescrito (o mesmo objeto se nada mudou)
     */
    public static String rewrite(String text, int from, Function<Wikilink, String> newTarget) {
        List<Wikilink> links = WikilinkParser.parse(text, from);
        List<Object[]> edits = new ArrayList<>();
        for (Wikilink link : links) {
            String replacement = newTarget.apply(link);
            if (replacement != null && !replacement.equals(link.target())) {
                edits.add(new Object[] {link, replacement});
            }
        }
        if (edits.isEmpty()) {
            return text;
        }
        StringBuilder out = new StringBuilder(text.length() + 32);
        int cursor = 0;
        for (Object[] edit : edits) {
            Wikilink link = (Wikilink) edit[0];
            out.append(text, cursor, link.targetStart()).append((String) edit[1]);
            cursor = link.targetEnd();
        }
        return out.append(text, cursor, text.length()).toString();
    }
}
