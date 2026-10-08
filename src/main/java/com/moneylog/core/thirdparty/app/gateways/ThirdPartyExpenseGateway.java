package com.moneylog.core.thirdparty.app.gateways;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;

public interface ThirdPartyExpenseGateway {

    ThirdPartyExpense insert(ThirdPartyExpense expense);

    Optional<ThirdPartyExpense> findByTransaction(UUID userId, UUID transactionId);

    List<ThirdPartyExpense> findAll(UUID userId);

    com.moneylog.core.thirdparty.domain.ThirdPartyReceipt insertReceipt(com.moneylog.core.thirdparty.domain.ThirdPartyReceipt receipt);

    List<com.moneylog.core.thirdparty.domain.ThirdPartyReceipt> findAllReceipts(UUID userId);

    Optional<ThirdPartyExpense> findById(UUID userId, UUID id);

    /** Remove só a marca (a marca não gera lançamento, então nada do razão muda). */
    void delete(UUID userId, UUID id);
}
