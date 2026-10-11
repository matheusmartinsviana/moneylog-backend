package com.moneylog.core.insights.domain;

import java.util.ArrayList;
import java.util.List;

import com.moneylog.core.insights.domain.InsufficientData.Reason;

public final class InsightSink {

    private final List<Insight> insights = new ArrayList<>();
    private final List<InsufficientData> insufficient = new ArrayList<>();

    public void emit(Insight insight) {
        insights.add(insight);
    }

    public void insufficient(String type, Reason reason) {
        insufficient.add(new InsufficientData(type, reason, null));
    }

    public void insufficient(String type, Reason reason, int missing) {
        insufficient.add(new InsufficientData(type, reason, Math.max(0, missing)));
    }

    public List<Insight> insights() {
        return List.copyOf(insights);
    }

    public List<InsufficientData> insufficient() {
        return List.copyOf(insufficient);
    }
}
