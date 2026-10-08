package com.moneylog.core.thirdparty.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;

public interface ThirdPartyExpenseGateway {

    ThirdPartyExpense insert(ThirdPartyExpense expense);

    Optional<ThirdPartyExpense> findByTransaction(UUID userId, UUID transactionId);

    List<ThirdPartyExpense> findAll(UUID userId);
}
