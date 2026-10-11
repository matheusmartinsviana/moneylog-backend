package com.moneylog.core.finance.app.dashboard;

import com.moneylog.core.finance.domain.DashboardSummary;

public record DashboardView(DashboardSummary summary, boolean hasAccounts) {
}
