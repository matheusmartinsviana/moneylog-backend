package com.moneylog.core.notification.app;

import java.time.LocalDate;
import java.time.LocalTime;
import java.util.UUID;

import com.moneylog.core.finance.app.gateways.CommitmentSourcesGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.domain.CommitmentSchedule;
import com.moneylog.core.finance.domain.DashboardSummary;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.goal.app.gateways.GoalGateway;
import com.moneylog.core.goal.domain.Goal;
import com.moneylog.core.notification.app.gateways.NotificationSettingsGateway;
import com.moneylog.core.notification.app.gateways.ReminderGateway;
import com.moneylog.core.notification.domain.GenerationContext;
import com.moneylog.core.notification.domain.NotificationSettings;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;

public class GenerationContextLoader {

    private final FinancialRecordsGateway records;
    private final CommitmentSourcesGateway sources;
    private final GoalGateway goals;
    private final ReminderGateway reminders;
    private final NotificationSettingsGateway settings;
    private final TimeProvider time;

    public GenerationContextLoader(FinancialRecordsGateway records, CommitmentSourcesGateway sources, GoalGateway goals, ReminderGateway reminders,
                                   NotificationSettingsGateway settings, TimeProvider time) {
        this.records = records;
        this.sources = sources;
        this.goals = goals;
        this.reminders = reminders;
        this.settings = settings;
        this.time = time;
    }

    public GenerationContext load(UUID userId) {
        LocalDate today = time.today();
        LocalTime localTime = time.now().atZone(time.zone()).toLocalTime();
        NotificationSettings prefs = settings.find(userId).orElseGet(() -> NotificationSettings.defaults(userId));
        FinancialRecords loaded = records.load(userId);
        var recurrences = sources.recurrences(userId);
        CommitmentSchedule schedule = CommitmentSchedule.of(loaded, recurrences, today);
        DashboardSummary summary = DashboardSummary.of(loaded, recurrences, today, today);
        return new GenerationContext(userId, today, localTime, prefs, schedule, goals.findAll(userId).stream().map(Versioned::value).toList(),
                reminders.openUntil(userId, today), summary.balances().available(), schedule.committed(Horizon.D7));
    }
}
