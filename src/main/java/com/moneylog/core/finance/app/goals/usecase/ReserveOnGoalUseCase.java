package com.moneylog.core.finance.app.goals.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.goals.GoalDetails;
import com.moneylog.core.sk.money.Money;

public interface ReserveOnGoalUseCase {

    /** Escopo da chave de idempotência (S-244), próprio desta rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/goals/{id}/reservations";

    /**
     * Reserva (valor positivo) ou libera (negativo) dinheiro de uma conta na meta. <b>Reserva lógica</b> (S-147): não cria
     * movimentação nem lançamento e não muda o saldo. Reserva acima do saldo é aceita e sinalizada ({@code uncovered}, S-148). Devolve
     * a meta atualizada. Mesma chave + mesmo comando devolve a meta (estado atual) sem reservar de novo.
     */
    GoalDetails handle(ReserveOnGoalCommand command);

    /** @param amount positivo = reserva; negativo = liberação */
    record ReserveOnGoalCommand(UUID userId, UUID goalId, UUID accountId, Money amount, LocalDate date, String note, UUID idempotencyKey) {
    }
}
