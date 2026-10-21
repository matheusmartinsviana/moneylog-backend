package com.moneylog.core.quickentry.app;

public record IssuedQuickEntryToken(QuickEntryTokenView view, String secret) {

    @Override
    public String toString() {
        return "IssuedQuickEntryToken[prefix=" + view.token().prefix() + "]";
    }
}
