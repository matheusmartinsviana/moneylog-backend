package com.moneylog.core.commitment.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;

public interface MarkScheduleItemPaidUseCase {

    /**
     * Marca um item do cronograma como pago em {@code paidOn}. Só o acompanhamento: <b>nenhuma conta é movimentada</b> (o pagamento real,
     * quando tem conta de origem, é outra movimentação). Já pago → {@code 422 COMMITMENT_SCHEDULE_ITEM_ALREADY_PAID}. Devolve o
     * compromisso atualizado.
     */
    CommitmentView handle(MarkScheduleItemPaidCommand command);

    record MarkScheduleItemPaidCommand(UUID userId, UUID id, int position, LocalDate paidOn, long version) {
    }
}
