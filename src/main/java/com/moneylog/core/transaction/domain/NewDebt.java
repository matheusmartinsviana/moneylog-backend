package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.domain.Person;

/**
 * Dados da dívida que nasce junto com a movimentação de origem ({@code LOAN_GIVEN} ou captação, S-41). O principal é o
 * valor da movimentação e a data da dívida é a da movimentação.
 *
 * @param debtId       id gerado pela aplicação
 * @param expectedDate previsão de pagamento/cobrança (opcional; sem ela a dívida fica fora dos horizontes)
 */
public record NewDebt(UUID debtId, Person person, LocalDate expectedDate, String description) {
}
