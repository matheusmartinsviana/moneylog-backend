package com.moneylog.core.note.domain;

import java.util.Locale;

/**
 * Um {@code [[wikilink]]} do corpo (S-158): {@code [[nome]]}, {@code [[pasta/nome]]}, {@code [[nome|alias]]}, {@code [[nome#seção]]} e o
 * embed {@code ![[arquivo]]}. Os índices apontam para o texto original, para a reescrita trocar só o alvo e preservar o resto.
 *
 * @param start       início do link no texto (no {@code !}, se for embed)
 * @param end         fim exclusivo (depois de {@code ]]})
 * @param targetStart início do alvo (sem espaços nas pontas)
 * @param targetEnd   fim exclusivo do alvo
 * @param target      alvo como escrito ({@code projetos/MG Tech})
 * @param section     parte depois de {@code #} (guardada e ignorada na resolução) ou {@code null}
 * @param alias       parte depois de {@code |} ou {@code null}
 */
public record Wikilink(int start, int end, int targetStart, int targetEnd, String target, String section, String alias, boolean embed) {

    /** Alvo sem a extensão {@code .md}, sem barra inicial, sem caixa e sem acento: a chave de resolução. */
    public String foldedTarget() {
        return TextFolding.fold(withoutExtension(target).replaceFirst("^/+", "").strip());
    }

    /** {@code true} se o alvo foi escrito como caminho ({@code pasta/nome}). */
    public boolean isPathForm() {
        return target.replaceFirst("^/+", "").indexOf('/') >= 0;
    }

    /** {@code true} se o alvo foi escrito com {@code .md}. */
    public boolean hasMarkdownExtension() {
        return target.toLowerCase(Locale.ROOT).endsWith(NotePath.EXTENSION);
    }

    static String withoutExtension(String target) {
        return target.toLowerCase(Locale.ROOT).endsWith(NotePath.EXTENSION)
                ? target.substring(0, target.length() - NotePath.EXTENSION.length()) : target;
    }
}
