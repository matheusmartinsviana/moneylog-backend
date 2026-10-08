package com.moneylog.core.finance.app.recurrences.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface RealizeOccurrenceUseCase {

    /**
     * Confirma uma ocorrência (S-20, S-140): cria a movimentação na <b>data da ocorrência</b>, com a origem {@code RECURRENCE} e o
     * par {@code (recurrenceId, occurrenceDate)}. Valor padrão = o do modelo; {@code actualAmount} troca (conta de luz). Conta:
     * {@code EXPENSE}/{@code INCOME}. Cartão: {@code CARD_PURCHASE} em 1 parcela pela alocação normal (S-14, S-50). A ocorrência sai
     * dos compromissos e o valor passa a vir só da movimentação (ou da fatura): sem dupla contagem.
     *
     * <p>{@code idempotencyKey}: a mesma chave com o mesmo comando devolve a mesma movimentação; com outro comando, 409. Ordem de
     * travas: a linha da recorrência → o cartão → as contas. Duas realizações da mesma ocorrência se serializam na recorrência; a
     * segunda recebe {@code RECURRENCE_OCCURRENCE_ALREADY_REALIZED} (o índice único {@code uq_transactions_recurrence_occurrence} é a
     * última barreira).
     */
    TransactionDetails handle(RealizeOccurrenceCommand command);

    /**
     * @param actualAmount   valor real; {@code null} usa o do modelo
     * @param idempotencyKey chave de idempotência (obrigatória na rota HTTP)
     */
    record RealizeOccurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount, UUID idempotencyKey,
                                    UUID accountId, com.moneylog.core.transaction.domain.PaymentMethod method) {

        public RealizeOccurrenceCommand(UUID userId, UUID recurrenceId, LocalDate occurrenceDate, Money actualAmount, UUID idempotencyKey) {
            this(userId, recurrenceId, occurrenceDate, actualAmount, idempotencyKey, null, null);
        }
    }

    /** Escopo da chave de idempotência (a tabela é compartilhada com as demais rotas de criação). */
    String IDEMPOTENCY_SCOPE = "POST /v1/recurrences/{id}/occurrences/{date}/realize";
}
