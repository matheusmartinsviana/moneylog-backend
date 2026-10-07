package com.moneylog.core.note.domain;

import java.util.Objects;
import java.util.UUID;
import java.util.regex.Pattern;

import com.moneylog.core.note.exception.NoteExceptions.NoteLinkInvalidException;

/**
 * Vínculo de uma nota (D1 §5, S-160). Duas origens:
 * <ul>
 *   <li>{@code WIKILINK}: alvo sempre {@code NOTE}; {@code text} é o alvo como escrito e {@code targetId} a nota resolvida (ou {@code null}
 *       = pendente). Recalculado quando o corpo muda ou o vault muda de forma.</li>
 *   <li>{@code EXPLICIT}: tipo + {@code targetId} (ou {@code targetKey} no resumo mensal). Alvo que não existe (só vem de importação)
 *       fica <b>pendente</b>: {@code targetId} e {@code targetKey} nulos e o valor original em {@code text}. Nunca há dupla referência.</li>
 * </ul>
 */
public record NoteLink(LinkOrigin origin, NoteLinkType type, UUID targetId, String targetKey, String text, String alias, String section,
                       boolean embed, String context) {

    private static final Pattern MONTH = Pattern.compile("^(20[0-9]{2}|2100)-(0[1-9]|1[0-2])$");
    private static final int MAX_TEXT = 255;

    public NoteLink {
        Objects.requireNonNull(origin, "origin");
        Objects.requireNonNull(type, "type");
        if (origin == LinkOrigin.WIKILINK && (type != NoteLinkType.NOTE || text == null || targetKey != null)) {
            throw new NoteLinkInvalidException("wikilink precisa de alvo NOTE e texto.");
        }
        if (targetId != null && targetKey != null) {
            throw new NoteLinkInvalidException("o vínculo não pode ter id e chave ao mesmo tempo.");
        }
        if (origin == LinkOrigin.EXPLICIT && targetId == null && targetKey == null && text == null) {
            throw new NoteLinkInvalidException("vínculo sem alvo.");
        }
        if (origin == LinkOrigin.EXPLICIT && (targetId != null || targetKey != null) && text != null) {
            throw new NoteLinkInvalidException("vínculo resolvido não guarda o texto original.");
        }
        if (targetKey != null && (!type.usesKey() || !isValidMonth(targetKey))) {
            throw new NoteLinkInvalidException("chave de resumo mensal precisa ser AAAA-MM entre 2000-01 e 2100-12.");
        }
        if (targetId != null && type.usesKey()) {
            throw new NoteLinkInvalidException("resumo mensal usa 'key', não 'id'.");
        }
        if (text != null && text.codePointCount(0, text.length()) > MAX_TEXT) {
            throw new NoteLinkInvalidException("texto do vínculo acima de " + MAX_TEXT + " caracteres.");
        }
    }

    public enum State { RESOLVED, PENDING }

    public static boolean isValidMonth(String key) {
        return key != null && MONTH.matcher(key).matches();
    }

    /** Wikilink do corpo; {@code resolved} nulo = pendente. */
    public static NoteLink wikilink(Wikilink link, UUID resolved, String context) {
        return new NoteLink(LinkOrigin.WIKILINK, NoteLinkType.NOTE, resolved, null, link.target(), cap(link.alias()), cap(link.section()), link.embed(),
                context);
    }

    /** Vínculo explícito já validado contra um alvo existente. */
    public static NoteLink explicit(NoteLinkType type, UUID targetId) {
        return new NoteLink(LinkOrigin.EXPLICIT, type, Objects.requireNonNull(targetId, "targetId"), null, null, null, null, false, null);
    }

    public static NoteLink explicitMonth(String key) {
        return new NoteLink(LinkOrigin.EXPLICIT, NoteLinkType.MONTHLY_SUMMARY, null, Objects.requireNonNull(key, "key"), null, null, null, false,
                null);
    }

    /** Vínculo explícito para um alvo que não existe (importação): guarda o valor original para a exportação devolvê-lo. */
    public static NoteLink pending(NoteLinkType type, String raw) {
        return new NoteLink(LinkOrigin.EXPLICIT, type, null, null, Objects.requireNonNull(raw, "raw"), null, null, false, null);
    }

    private static String cap(String value) {
        return CodePoints.truncate(value, MAX_TEXT);
    }

    public State state() {
        if (origin == LinkOrigin.WIKILINK) {
            return targetId != null ? State.RESOLVED : State.PENDING;
        }
        return targetId != null || targetKey != null ? State.RESOLVED : State.PENDING;
    }

    /** Wikilink com outra resolução (ou nenhuma). */
    public NoteLink withTarget(UUID resolved) {
        return new NoteLink(origin, type, resolved, targetKey, text, alias, section, embed, context);
    }

    /** Identidade de um wikilink pelo alvo escrito: sem {@code .md}, sem caixa e sem acento. */
    public static String wikilinkIdentity(String target) {
        return "W:" + TextFolding.fold(Wikilink.withoutExtension(target));
    }

    /** Chave que identifica o mesmo vínculo (para tirar duplicatas). */
    public String identity() {
        if (origin == LinkOrigin.WIKILINK) {
            return wikilinkIdentity(text);
        }
        return "E:" + type + ":" + (targetId != null ? targetId : targetKey != null ? targetKey : "?" + text);
    }
}
