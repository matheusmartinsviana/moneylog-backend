package com.moneylog.core.card.domain;

import com.moneylog.core.sk.money.Money;

/**
 * Limite disponível calculado (S-46, S-11).
 *
 * @param available     limite + saldo do livro CARD em todas as datas (pode ser negativo ou passar do limite); {@code null} sem limite definido
 * @param overLimit     disponível negativo: sinalizado como risco, sem bloquear (S-11)
 * @param creditBalance crédito no cartão (saldo credor do livro CARD), 0 se não houver
 */
public record AvailableLimit(Money available, boolean overLimit, Money creditBalance) {
}
