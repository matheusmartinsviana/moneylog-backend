package com.moneylog.core.notification.domain;

import java.util.Objects;

import com.moneylog.core.notification.exception.NotificationExceptions.NotificationThresholdInvalidException;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;

public record NotificationAlertSettings(Integer subscriptionUnusedDays, int invoiceDueLeadDays, Money documentDivergenceTolerance) {

    public static final int UNUSED_DAYS_MIN = 1;
    public static final int UNUSED_DAYS_MAX = 3650;
    public static final int LEAD_DAYS_MIN = 1;
    public static final int LEAD_DAYS_MAX = 30;
    public static final int DEFAULT_LEAD_DAYS = 3;

    public static final NotificationAlertSettings DEFAULTS = new NotificationAlertSettings(null, DEFAULT_LEAD_DAYS, Money.zero(CurrencyCode.BRL));

    public NotificationAlertSettings {
        Objects.requireNonNull(documentDivergenceTolerance, "documentDivergenceTolerance");
        if (subscriptionUnusedDays != null && (subscriptionUnusedDays < UNUSED_DAYS_MIN || subscriptionUnusedDays > UNUSED_DAYS_MAX)) {
            throw new NotificationThresholdInvalidException("subscriptionUnusedDays deve ficar entre " + UNUSED_DAYS_MIN + " e " + UNUSED_DAYS_MAX + ".");
        }
        if (invoiceDueLeadDays < LEAD_DAYS_MIN || invoiceDueLeadDays > LEAD_DAYS_MAX) {
            throw new NotificationThresholdInvalidException("invoiceDueLeadDays deve ficar entre " + LEAD_DAYS_MIN + " e " + LEAD_DAYS_MAX + ".");
        }
        if (documentDivergenceTolerance.isNegative() || !CurrencyCode.BRL.equals(documentDivergenceTolerance.currency())) {
            throw new NotificationThresholdInvalidException("documentDivergenceTolerance não pode ser negativa e é em BRL.");
        }
    }

    public NotificationAlertSettings merge(NotificationAlertSettings.Patch patch) {
        if (patch == null) {
            return this;
        }
        return new NotificationAlertSettings(patch.unusedDaysGiven() ? patch.subscriptionUnusedDays() : subscriptionUnusedDays,
                patch.invoiceDueLeadDays() == null ? invoiceDueLeadDays : patch.invoiceDueLeadDays(),
                patch.documentDivergenceTolerance() == null ? documentDivergenceTolerance : patch.documentDivergenceTolerance());
    }

    public record Patch(boolean unusedDaysGiven, Integer subscriptionUnusedDays, Integer invoiceDueLeadDays, Money documentDivergenceTolerance) {
    }
}
