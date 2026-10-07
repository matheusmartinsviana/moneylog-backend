package com.moneylog.core.search.domain;

import java.util.Optional;

import com.moneylog.core.sk.text.PlainText;

/**
 * Termo da busca global (redesign, S-287): aparado, de 2 a 100 <b>pontos de código</b>, sem nenhum caractere de controle (NUL, que o
 * Postgres rejeita com erro, e os demais controles, que só servem para estragar log e relatório). {@code %}, {@code _} e {@code \} são
 * texto comum: quem monta a consulta os escapa.
 */
public final class SearchTerm {

    public static final int MIN_LENGTH = 2;
    public static final int MAX_LENGTH = 100;

    private SearchTerm() {
    }

    /** O termo aparado, ou vazio se for inválido (nulo, curto, longo ou com caractere de controle). Nunca ecoa o valor. */
    public static Optional<String> normalize(String raw) {
        if (raw == null || PlainText.hasControlCharacters(raw, false)) {
            return Optional.empty();
        }
        String term = raw.strip();
        int length = term.codePointCount(0, term.length());
        return length < MIN_LENGTH || length > MAX_LENGTH ? Optional.empty() : Optional.of(term);
    }
}
