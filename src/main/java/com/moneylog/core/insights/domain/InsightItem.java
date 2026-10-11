package com.moneylog.core.insights.domain;

/** Insight com o id público e se está dispensado agora. */
public record InsightItem(Insight insight, String id, boolean dismissed) {
}
