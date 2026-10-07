package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.finance.app.debts.DebtOverviewView;

public interface GetDebtOverviewUseCase {

    /**
     * Quanto o usuário ainda deve (cartões + pessoas + outras), o custo fixo ao lado (fora do total), a comparação com a renda e o
     * cronograma de vencimentos por mês. Só leitura, sobre o carregamento único dos registros (um instantâneo).
     *
     * @param months horizonte do cronograma, 1..36
     */
    DebtOverviewView handle(GetDebtOverviewQuery query);

    record GetDebtOverviewQuery(UUID userId, int months) {
    }
}
