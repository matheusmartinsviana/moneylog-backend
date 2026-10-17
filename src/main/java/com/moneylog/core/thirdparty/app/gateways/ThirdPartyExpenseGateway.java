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

    Optional<com.moneylog.core.thirdparty.domain.ThirdPartyReceipt> findReceipt(UUID userId, UUID id);

    void ledgerReceipt(UUID userId, UUID id, java.time.LocalDate receivedOn);

    boolean deleteReceipt(UUID userId, UUID id);

    Optional<ThirdPartyExpense> findById(UUID userId, UUID id);

    void delete(UUID userId, UUID id);

    List<com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth> findExpectedMonths(UUID userId);

    Optional<com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth> findExpectedMonth(UUID userId, UUID installmentId, UUID personId);

    com.moneylog.core.thirdparty.domain.ReceivableExpectedMonth saveExpectedMonth(UUID userId, UUID transactionId, UUID installmentId,
                                                                                  UUID personId, com.moneylog.core.sk.time.Competence month,
                                                                                  Long expectedVersion);

    void deleteExpectedMonth(UUID userId, UUID installmentId, UUID personId);
}
