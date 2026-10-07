package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.CardInvoices;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionHeader;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.InvalidTransactionOperationException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionAlreadyVoidedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;
import com.moneylog.core.transaction.exception.TransactionExceptions.VoidReasonRequiredException;

/**
 * Cancelamento das parcelas restantes (T57). Ordem de travas (a global): a linha da movimentação → a linha do cartão; a
 * cadeia só é lida depois das travas, e a decisão ("fatura {@code FUTURE}/{@code OPEN}") é tomada sobre o estado
 * <b>anterior</b> ao cancelamento. Grava, na mesma transação: a movimentação (versão e parcelas), a revisão com o estado
 * anterior e o motivo, e a desativação dos lançamentos das parcelas canceladas.
 */
public class CancelInstallmentsUseCaseImpl implements CancelInstallmentsUseCase {

    private final TransactionGateway transactions;
    private final CardGateway cards;
    private final TransactionRevisionGateway revisions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final TimeProvider time;

    public CancelInstallmentsUseCaseImpl(TransactionGateway transactions, CardGateway cards, TransactionRevisionGateway revisions,
                                         LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, TimeProvider time) {
        this.transactions = transactions;
        this.cards = cards;
        this.revisions = revisions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(CancelInstallmentsCommand command) {
        UUID userId = command.userId();
        String reason = TransactionHeader.optionalText(command.reason(), "cancelReason", Transaction.VOID_REASON_MAX_LENGTH);
        if (reason == null) {
            throw new VoidReasonRequiredException();
        }
        transactions.lockForUpdate(userId, command.transactionId());
        Transaction current = transactions.findById(userId, command.transactionId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(command.transactionId()));
        if (current.status() == TransactionStatus.VOIDED) {
            throw new TransactionAlreadyVoidedException();
        }
        if (current.type() != TransactionType.CARD_PURCHASE) {
            throw new InvalidTransactionOperationException("TRANSACTION_NOT_CANCELABLE", "Só uma compra no cartão ativa tem parcelas para cancelar.");
        }
        cards.lockForUpdate(userId, current.cardId());
        CreditCard card = cards.findById(userId, current.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(current.cardId()));
        List<Transaction> ofCard = transactions.findAllOfCard(userId, card.id());
        InvoiceChain chain = CardInvoices.chainFor(card, ofCard);
        // a cópia lida junto com a cadeia é a que muda; `current` fica intacta para a revisão (estado anterior)
        Transaction purchase = ofCard.stream().filter(t -> t.id().equals(current.id())).findFirst()
                .orElseThrow(() -> new TransactionNotFoundException(current.id()));
        List<UUID> before = purchase.installments().stream().filter(i -> !i.isCanceled()).map(TransactionInstallment::id).toList();
        Instant now = time.now();
        // S-218: a soma é lida sob a trava da movimentação (reembolsos ligados a ela se serializam nessa linha)
        Money activeRefunds = transactions.sumActiveRefundsOf(userId, purchase.id());
        CardTransactions.cancelRemainingInstallments(purchase, chain, time.today(), now, activeRefunds);
        List<UUID> canceled = purchase.installments().stream().filter(i -> i.isCanceled() && before.contains(i.id()))
                .map(TransactionInstallment::id).toList();
        Persisted<Transaction> saved = transactions.update(purchase);
        revisions.save(userId, current, Math.toIntExact(current.version()) + 1, now, REVISION_CHANGE, reason);
        ledger.deactivateByInstallments(userId, purchase.id(), canceled, CANCEL_REASON, now);
        return assembler.assemble(saved);
    }
}
