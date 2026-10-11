package com.moneylog.core.insights.app.gateways;

import java.time.Instant;
import java.util.Collection;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.insights.domain.Dismissal;

public interface InsightDismissalGateway {

    List<Dismissal> findActive(UUID userId);

    void insert(Dismissal dismissal);

    int revoke(UUID userId, Collection<UUID> ids, Instant at);
}
