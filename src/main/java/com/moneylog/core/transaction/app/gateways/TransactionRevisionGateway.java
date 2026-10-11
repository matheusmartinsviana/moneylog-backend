package com.moneylog.core.transaction.app.gateways;

import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.transaction.domain.Transaction;

public interface TransactionRevisionGateway {

    void save(UUID userId, Transaction previous, int revision, Instant at);

    void save(UUID userId, Transaction previous, int revision, Instant at, String change, String reason);
}
