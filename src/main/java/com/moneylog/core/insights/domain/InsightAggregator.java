package com.moneylog.core.insights.domain;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.HashSet;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

public final class InsightAggregator {

    private InsightAggregator() {
    }

    public static final Comparator<InsightItem> ORDER = Comparator
            .comparing((InsightItem i) -> i.insight().severity())
            .thenComparing((InsightItem i) -> i.insight().priority(), Comparator.reverseOrder())
            .thenComparing((InsightItem i) -> i.insight().magnitudeCents(), Comparator.reverseOrder())
            .thenComparing((InsightItem i) -> i.insight().type())
            .thenComparing(InsightItem::id);

    public static List<InsightItem> aggregate(UUID userId, List<Insight> insights, List<Dismissal> dismissals, LocalDate today,
                                              boolean includeDismissed) {
        Set<String> dueRefs = new HashSet<>();
        for (Insight insight : insights) {
            if (DailyInsights.DUE_SOON.equals(insight.type())) {
                dueRefs.addAll(insight.factRefs());
            }
        }
        Map<String, InsightItem> byId = new LinkedHashMap<>();
        for (Insight insight : insights) {
            if (DailyInsights.CARD_CLOSING_SOON.equals(insight.type()) && dueRefs.contains(insight.subjectId())) {
                continue;
            }
            String fingerprint = InsightIds.fingerprint(userId, insight);
            boolean dismissed = dismissals.stream().anyMatch(d -> d.hides(fingerprint, insight, today));
            if (dismissed && !includeDismissed) {
                continue;
            }
            byId.putIfAbsent(InsightIds.PREFIX + fingerprint, new InsightItem(insight, InsightIds.PREFIX + fingerprint, dismissed));
        }
        return byId.values().stream().sorted(ORDER).toList();
    }
}
