package com.moneylog.core.note.domain;

public enum NoteLinkType {
    TRANSACTION, ACCOUNT, CARD, INVOICE, PERSON, DEBT, GOAL, MONTHLY_SUMMARY, NOTE;

    public boolean usesKey() {
        return this == MONTHLY_SUMMARY;
    }

    public boolean isFinancialEntity() {
        return this != NOTE && this != MONTHLY_SUMMARY;
    }
}
