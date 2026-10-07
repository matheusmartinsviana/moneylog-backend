package com.moneylog.core.note.domain;

/** De onde veio o vínculo: extraído do corpo ({@code WIKILINK}, recalculado a cada edição) ou criado pelo usuário ({@code EXPLICIT}). */
public enum LinkOrigin {
    WIKILINK, EXPLICIT
}
