package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.domain.ThirdPartyReceivables;
import com.moneylog.core.sk.time.Competence;

public interface GetThirdPartyReceivablesUseCase {

    /**
     * "Dinheiro a receber de terceiros" por pessoa (a receber, recebido, pendente) e a visão do mês (bruto x terceiros x líquido).
     * {@code competence} {@code null} = mês atual.
     */
    ThirdPartyReceivables.Overview handle(GetThirdPartyReceivablesQuery query);

    record GetThirdPartyReceivablesQuery(UUID userId, Competence competence) {
    }
}
