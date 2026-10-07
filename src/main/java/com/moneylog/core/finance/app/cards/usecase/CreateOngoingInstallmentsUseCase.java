package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.sk.money.Money;

public interface CreateOngoingInstallmentsUseCase {

    /**
     * Cadastra uma compra parcelada que já estava em andamento antes do app (ADR-013, P5, P7): grava <b>só as parcelas que
     * faltam</b>, sem consumo retroativo. Sem idempotência no MVP: repetir o mesmo pedido cria outra compra.
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.CardNotFoundException                    cartão de outro usuário
     * @throws com.moneylog.core.card.exception.CardExceptions.CardArchivedException                    cartão arquivado
     * @throws com.moneylog.core.card.exception.CardExceptions.OngoingInvalidCountException             faltam mais que o total
     * @throws com.moneylog.core.card.exception.CardExceptions.OngoingFirstInvoiceNotPayableException   fatura da próxima parcela já paga
     */
    TransactionDetails handle(CreateOngoingInstallmentsCommand command);

    /**
     * @param installmentAmount     valor de <b>cada</b> parcela
     * @param remainingInstallments quantas faltam pagar, a próxima inclusive ("parcela 3 de 10" = 8)
     * @param firstInvoiceDate      data dentro do ciclo da fatura da próxima parcela; {@code null} = hoje (fatura corrente)
     * @param categoryId            {@code null} = sem categoria (consumo 0, contrapartida EQUITY)
     */
    record CreateOngoingInstallmentsCommand(UUID userId, UUID cardId, String description, Money installmentAmount,
                                            int totalInstallments, int remainingInstallments, LocalDate firstInvoiceDate,
                                            UUID categoryId) {
    }
}
