package com.moneylog.core.finance.app.statement;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.domain.OwnerStatement;
import com.moneylog.core.sk.time.Competence;

public interface GetOwnerStatementUseCase {

    OwnerStatement handle(GetOwnerStatementQuery query);

    record GetOwnerStatementQuery(UUID userId, Competence from, Integer months, LocalDate asOf) {
    }
}
