package com.moneylog.core.finance.app.cards;

import java.time.LocalDate;

public record BillingChangePreviewView(LocalDate effectiveFromClosingDate, LocalDate firstNewClosingDate, LocalDate firstNewDueDate,
                                       int movedInstallments, int movedCredits) {
}
