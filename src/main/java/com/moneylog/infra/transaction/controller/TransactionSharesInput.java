package com.moneylog.infra.transaction.controller;

import java.util.List;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.usecase.UpdateTransactionSharesUseCase.UpdateTransactionSharesCommand;
import com.moneylog.core.transaction.domain.SplitMode;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record TransactionSharesInput(@NotNull SplitMode splitMode,
                                     @NotNull @Size(max = 20) @Valid List<@NotNull PersonShareInput> shares,
                                     @NotNull Long version) {

    public UpdateTransactionSharesCommand toCommand(UUID userId, UUID transactionId) {
        return new UpdateTransactionSharesCommand(userId, transactionId, version, splitMode, PersonShareInput.lines(shares));
    }
}
