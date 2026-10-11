package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.domain.ThirdPartyReceivables;
import com.moneylog.core.sk.time.Competence;

public interface GetThirdPartyReceivablesUseCase {

    ThirdPartyReceivables.Overview handle(GetThirdPartyReceivablesQuery query);

    record GetThirdPartyReceivablesQuery(UUID userId, Competence competence) {
    }
}
