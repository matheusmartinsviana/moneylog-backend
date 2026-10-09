package com.moneylog.core.recurrence.app.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.recurrence.app.RecurrenceView;
import com.moneylog.core.recurrence.domain.Frequency;
import com.moneylog.core.recurrence.domain.IntervalUnit;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionType;

public interface CreateRecurrenceUseCase {

    /**
     * Cria o modelo de uma {@code EXPENSE}, {@code INCOME} (conta + meio de pagamento) ou {@code CARD_PURCHASE} à vista (cartão, S-144),
     * com a agenda. Nada é realizado sozinho (S-20). Conta e cartão juntos, ou nenhum, → {@code RECURRENCE_INVALID}; conta arquivada
     * ou que não é {@code CASH}, cartão arquivado e categoria de tipo errado ou arquivada recusam como nas movimentações.
     */
    RecurrenceView handle(CreateRecurrenceCommand command);

    /** Escopo da chave de idempotência (S-244), próprio desta rota. */
    String IDEMPOTENCY_SCOPE = "POST /v1/recurrences";

    /**
     * @param idempotencyKey opcional: com a chave, repetir o mesmo comando devolve a mesma recorrência; outro comando é {@code IDEMPOTENCY_KEY_REUSED}
     * @param interval só em {@code CUSTOM} ({@code null} = 1 nos demais)
     * @param unit     só em {@code CUSTOM}
     * @param incomeCompetenceOffsetMonths só na receita: 0 (padrão, mesmo mês da data) ou 1 (a renda pertence ao mês seguinte, ADR-017);
     *                                     {@code null} = 0
     */
    record CreateRecurrenceCommand(UUID userId, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                   PaymentMethod method, UUID cardId, Frequency frequency, Integer interval, IntervalUnit unit,
                                   LocalDate startDate, LocalDate endDate, Integer maxOccurrences, UUID idempotencyKey,
                                   Integer incomeCompetenceOffsetMonths) {

        /** Sem chave de idempotência. */
        public CreateRecurrenceCommand(UUID userId, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, Frequency frequency, Integer interval, IntervalUnit unit,
                                       LocalDate startDate, LocalDate endDate, Integer maxOccurrences) {
            this(userId, type, amount, description, categoryId, accountId, method, cardId, frequency, interval, unit, startDate, endDate,
                    maxOccurrences, null, null);
        }

        /** Com chave de idempotência, sem deslocamento de competência. */
        public CreateRecurrenceCommand(UUID userId, TransactionType type, Money amount, String description, UUID categoryId, UUID accountId,
                                       PaymentMethod method, UUID cardId, Frequency frequency, Integer interval, IntervalUnit unit,
                                       LocalDate startDate, LocalDate endDate, Integer maxOccurrences, UUID idempotencyKey) {
            this(userId, type, amount, description, categoryId, accountId, method, cardId, frequency, interval, unit, startDate, endDate,
                    maxOccurrences, idempotencyKey, null);
        }
    }
}
