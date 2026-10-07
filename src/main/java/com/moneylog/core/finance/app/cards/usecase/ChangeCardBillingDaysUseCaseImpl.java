package com.moneylog.core.finance.app.cards.usecase;

import java.time.LocalDate;
import java.util.List;
import java.util.Objects;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.exception.CardExceptions.BillingChangeNotConfirmedException;
import com.moneylog.core.card.exception.CardExceptions.CardBillingChangeStaleException;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.cards.CardDetails;
import com.moneylog.core.finance.app.cards.CardViews;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.CardBillingChanges;
import com.moneylog.core.transaction.domain.Transaction;

/**
 * Mudança de fechamento/vencimento (T58). Uma transação: se qualquer passo falha (item sem fatura no calendário, erro de
 * gravação), o cartão, as regras, as parcelas e as linhas de fatura ficam como antes. A decisão é toda tomada em memória,
 * sobre a cadeia lida <b>depois</b> das travas, antes de gravar qualquer coisa ({@link CardBillingChanges}); depois grava, nesta
 * ordem: cartão e regras (as linhas de fatura novas usam o calendário novo) → movimentações realocadas → linhas de fatura
 * (datas atualizadas e podadas, ADR-006 §7).
 */
public class ChangeCardBillingDaysUseCaseImpl implements ChangeCardBillingDaysUseCase {

    private final CardGateway cards;
    private final AccountGateway accounts;
    private final TransactionGateway transactions;
    private final FinancialRecordsGateway records;
    private final TimeProvider time;

    public ChangeCardBillingDaysUseCaseImpl(CardGateway cards, AccountGateway accounts, TransactionGateway transactions,
                                            FinancialRecordsGateway records, TimeProvider time) {
        this.cards = cards;
        this.accounts = accounts;
        this.transactions = transactions;
        this.records = records;
        this.time = time;
    }

    @Override
    public CardDetails handle(ChangeCardBillingDaysCommand command) {
        // ordem global de travas: as linhas das movimentações do cartão → a linha do cartão → a conta
        transactions.lockAllOfCard(command.userId(), command.cardId());
        cards.lockForUpdate(command.userId(), command.cardId());
        // B2/S-215: o retrato acima foi tirado antes de a trava do cartão sair; compras criadas nesse intervalo entram agora
        // (só aparece o que é novo, em ordem de id). O que ainda assim se cruzar vira deadlock, que a API devolve como 409.
        transactions.lockAllOfCard(command.userId(), command.cardId());
        CreditCard card = cards.findById(command.userId(), command.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(command.cardId()));
        if (card.version() != command.version()) {
            throw new VersionConflictException(command.version(), card.version());
        }
        LocalDate today = time.today();
        if (command.confirmedEffectiveFrom() == null) {
            // valida os dias antes de pedir a confirmação: dia inválido é 422 CARD_INVALID_BILLING_DAY, não "falta confirmar"
            BillingChangeSupport.effectiveFromClosingDate(card, command.closingDay(), command.dueDay(), today);
            throw new BillingChangeNotConfirmedException();
        }
        if (!command.confirmedEffectiveFrom().equals(
                BillingChangeSupport.effectiveFromClosingDate(card, command.closingDay(), command.dueDay(), today))) {
            throw new CardBillingChangeStaleException();
        }
        if (!Objects.equals(card.defaultPaymentAccountId(), command.defaultPaymentAccountId())) {
            CardAccounts.requireUsablePaymentAccount(accounts, command.userId(), command.defaultPaymentAccountId());
        }
        List<Transaction> ofCard = transactions.findAllOfCard(command.userId(), card.id());
        card.updateDetails(command.name(), command.institution(), command.limit(), command.defaultPaymentAccountId());
        List<Transaction> changed = CardBillingChanges.changeBillingDays(card, ofCard, command.closingDay(), command.dueDay(), today);
        Persisted<CreditCard> saved = cards.save(card);
        for (Transaction moved : changed) {
            transactions.update(moved);
        }
        cards.refreshInvoiceRows(command.userId(), saved.value());
        return CardViews.of(saved, records.load(command.userId()), today);
    }
}
