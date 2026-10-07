package com.moneylog.core.finance.app.gateways;

import java.util.UUID;

import com.moneylog.core.finance.domain.FinancialRecords;

/**
 * Carregamento único dos registros financeiros de um usuário (S-79, T24/T25/T26a): contas, movimentações (ativas e
 * estornadas) e os lançamentos do razão. Lista, entries, dashboard e saldo das contas leem <b>todos</b> por aqui e
 * calculam com as mesmas calculadoras do {@code core.finance} ({@code records.ledger()}): não existe um segundo caminho
 * de cálculo (PR 01, regra 10). Cartões e dívidas ainda não são persistidos (Etapas 3 e 4) e vêm vazios.
 */
public interface FinancialRecordsGateway {

    FinancialRecords load(UUID userId);
}
