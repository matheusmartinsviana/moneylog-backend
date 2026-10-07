package com.moneylog.core.finance.app.recurrences.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface RealizeCardRecurrenceUseCase {

    /**
     * Realiza uma ocorrência de recorrência no cartão (T60, S-20, S-49): cria uma {@code CARD_PURCHASE} à vista (1 parcela)
     * com origem {@code RECURRENCE} e o par {@code (recurrenceId, occurrenceDate)}, pelo mesmo caminho da compra comum
     * ({@code CardTransactions.purchase}, que aloca pela fatura do ciclo e pelo status dela, com lançamento tardio, S-14,
     * S-50). A partir daí o valor da ocorrência vem <b>só</b> da fatura: a ocorrência sai da lista de compromissos
     * {@code RECURRENCE} (sem dupla contagem). A rota HTTP é {@code POST /v1/recurrences/{id}/occurrences/{date}/realize} (T80, {@link RealizeOccurrenceUseCase}).
     *
     * <p>Ordem de travas: a linha da recorrência → a linha do cartão; a cadeia é lida depois das travas.
     *
     * @param actualAmount valor real (ex.: a assinatura veio mais cara); {@code null} usa o do modelo
     * @throws com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException recorrência inexistente
     * @throws com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceAlreadyRealizedException já realizada
     * @throws com.moneylog.core.recurrence.exception.RecurrenceExceptions.OccurrenceSkippedException pulada
     * @throws com.moneylog.core.recurrence.exception.RecurrenceExceptions.NotAnOccurrenceException a data não é ocorrência
     * @throws com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceReferenceMismatchException cartão ou categoria
     *         que não são os do modelo (ou o modelo é de conta)
     */
    TransactionDetails handle(RealizeCardRecurrenceCommand command);

    /**
     * @param transactionId id da movimentação a criar ({@code null} = gerado aqui); a rota HTTP (T80) o passa para o
     *                      {@code Idempotency-Key} apontar para o recurso certo
     */
    record RealizeCardRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount,
                                        UUID transactionId) {

        public RealizeCardRecurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount) {
            this(userId, recurrenceId, occurrenceDate, actualAmount, null);
        }
    }
}
