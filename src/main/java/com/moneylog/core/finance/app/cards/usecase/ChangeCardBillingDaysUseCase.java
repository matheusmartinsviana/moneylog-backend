package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.sk.money.Money;

public interface ChangeCardBillingDaysUseCase {

    /**
     * O {@code PUT} do cartão com <b>mudança de fechamento e/ou vencimento</b> (S-42, S-128): a regra nova vale a partir da
     * fatura seguinte à corrente, e as parcelas e os créditos das faturas {@code FUTURE} são realocados
     * ({@code CardBillingChanges.changeBillingDays}) na <b>mesma transação</b> que grava o cartão, as regras, as
     * movimentações e as linhas de fatura. Tudo ou nada.
     *
     * <p>Ordem de travas: linhas das movimentações do cartão → linha do cartão → conta. O {@code confirmedEffectiveFrom} é o
     * da prévia; se a fatura corrente virou entre a prévia e esta chamada, a primeira fatura afetada é outra e a resposta é
     * {@code 409 CARD_BILLING_CHANGE_STALE} (a {@code version} não muda na virada do dia, então não detecta sozinha).
     *
     * @throws com.moneylog.core.card.exception.CardExceptions.BillingChangeNotConfirmedException sem {@code confirmedEffectiveFrom}
     * @throws com.moneylog.core.card.exception.CardExceptions.CardBillingChangeStaleException   a prévia ficou velha
     * @throws com.moneylog.core.sk.exception.VersionConflictException                           versão desatualizada
     */
    CardDetails handle(ChangeCardBillingDaysCommand command);

    record ChangeCardBillingDaysCommand(UUID userId, UUID cardId, String name, String institution, Money limit, int closingDay, int dueDay,
                                        UUID defaultPaymentAccountId, LocalDate confirmedEffectiveFrom, long version) {
    }
}
