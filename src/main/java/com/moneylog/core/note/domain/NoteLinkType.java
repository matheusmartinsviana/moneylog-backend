package com.moneylog.core.note.domain;

/** Tipo do alvo de um vínculo (S-160). {@code MONTHLY_SUMMARY} usa {@code key} ({@code AAAA-MM}); os demais, {@code id} (UUID). */
public enum NoteLinkType {
    TRANSACTION, ACCOUNT, CARD, INVOICE, PERSON, DEBT, GOAL, MONTHLY_SUMMARY, NOTE;

    public boolean usesKey() {
        return this == MONTHLY_SUMMARY;
    }

    /** Entidade financeira (alvo fora do vault). */
    public boolean isFinancialEntity() {
        return this != NOTE && this != MONTHLY_SUMMARY;
    }
}
