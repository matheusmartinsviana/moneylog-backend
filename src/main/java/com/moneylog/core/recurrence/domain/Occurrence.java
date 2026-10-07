package com.moneylog.core.recurrence.domain;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.sk.money.FlowDirection;
import com.moneylog.core.sk.money.Money;

/**
 * Ocorrência <b>prevista</b> de uma recorrência (D1 §2.7). Não é movimentação e não gera posting: só entra em
 * compromissos e projeções até ser realizada (S-20).
 */
public record Occurrence(UUID recurrenceId, LocalDate date, Money amount, FlowDirection direction, String description) {
}
