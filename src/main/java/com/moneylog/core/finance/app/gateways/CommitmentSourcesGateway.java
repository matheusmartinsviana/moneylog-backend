package com.moneylog.core.finance.app.gateways;

import java.util.Collection;
import java.util.UUID;

import com.moneylog.core.recurrence.domain.RecurringTransaction;

/**
 * Fontes de compromisso futuro que ainda não fazem parte dos {@code FinancialRecords} (S-64): as recorrências. Faturas
 * e dívidas já entram pelos próprios {@code FinancialRecords} (cartões e dívidas vêm vazios até as Etapas 3 e 4). Nesta
 * etapa nenhuma fonte é persistida e o adaptador devolve vazio; quando elas chegarem só o adaptador muda.
 */
public interface CommitmentSourcesGateway {

    Collection<RecurringTransaction> recurrences(UUID userId);
}
