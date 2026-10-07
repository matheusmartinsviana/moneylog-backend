package com.moneylog.core.transaction.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.sk.money.Money;

/**
 * Parte de uma despesa dividida (D1 §2.6.1, S-40): o que {@code person} deve ao usuário. Vira uma dívida
 * {@code RECEIVABLE} cuja origem é a própria despesa.
 *
 * @param debtId       id da dívida, gerado pela aplicação
 * @param expectedDate previsão de cobrança (opcional)
 * @param description  descrição da dívida (opcional; sem ela, a da despesa)
 */
public record ExpenseShare(UUID debtId, Person person, Money amount, LocalDate expectedDate, String description) {
}
