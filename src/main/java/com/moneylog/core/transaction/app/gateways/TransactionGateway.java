package com.moneylog.core.transaction.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.domain.Transaction;

public interface TransactionGateway {

    Persisted<Transaction> insert(Transaction transaction);

    Persisted<Transaction> update(Transaction transaction);

    void lockForUpdate(UUID userId, UUID id);

    void lockAllOfCard(UUID userId, UUID cardId);

    Optional<Persisted<Transaction>> findById(UUID userId, UUID id);

    Slice<Persisted<Transaction>> list(UUID userId, TransactionFilter filter, PageQuery page);

    List<Transaction> findAllOfCard(UUID userId, UUID cardId);

    Money sumActiveRefundsOf(UUID userId, UUID originalId);

    List<Transaction> findActiveRefundsOf(UUID userId, UUID originalId);
}
