package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.app.gateways.CardGateway;
import com.moneylog.core.card.domain.CreditCard;
import com.moneylog.core.card.domain.InvoiceChain;
import com.moneylog.core.card.domain.TransactionInstallment;
import com.moneylog.core.card.exception.CardExceptions.CardNotFoundException;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.ShareSettlement;
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

public class CancelInstallmentsUseCaseImpl implements CancelInstallmentsUseCase {

    private final TransactionGateway transactions;
    private final CardGateway cards;
    private final TransactionRevisionGateway revisions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final TimeProvider time;
    private final ShareSettlement shareSettlement;

    public CancelInstallmentsUseCaseImpl(TransactionGateway transactions, CardGateway cards, TransactionRevisionGateway revisions,
                                         LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, TimeProvider time) {
        this(transactions, cards, revisions, ledger, assembler, time, null);
    }

    @SuppressWarnings("java:S107")
    public CancelInstallmentsUseCaseImpl(TransactionGateway transactions, CardGateway cards, TransactionRevisionGateway revisions,
                                         LedgerWriteGateway ledger, TransactionDetailsAssembler assembler, TimeProvider time,
                                         ShareSettlement shareSettlement) {
        this.shareSettlement = shareSettlement;
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
        boolean shared = shareSettlement != null && current.purchaseShares() != null;
        if (shared) {
            shareSettlement.lockPersons(userId, current.purchaseShares().personIds());
        }
        CreditCard card = cards.findById(userId, current.cardId()).map(Persisted::value)
                .orElseThrow(() -> new CardNotFoundException(current.cardId()));
        List<Transaction> ofCard = transactions.findAllOfCard(userId, card.id());
        InvoiceChain chain = CardInvoices.chainFor(card, ofCard);
        Transaction purchase = ofCard.stream().filter(t -> t.id().equals(current.id())).findFirst()
                .orElseThrow(() -> new TransactionNotFoundException(current.id()));
        List<UUID> before = purchase.installments().stream().filter(i -> !i.isCanceled()).map(TransactionInstallment::id).toList();
        Instant now = time.now();
        Money activeRefunds = transactions.sumActiveRefundsOf(userId, purchase.id());
        CardTransactions.cancelRemainingInstallments(purchase, chain, time.today(), now, activeRefunds);
        List<UUID> canceled = purchase.installments().stream().filter(i -> i.isCanceled() && before.contains(i.id()))
                .map(TransactionInstallment::id).toList();
        ShareSettlement.Plan conversion = shared
                ? shareSettlement.planConversion(userId, purchase, shareSettlement.owedNet(userId, purchase), time.today())
                : ShareSettlement.Plan.NONE;
        Persisted<Transaction> saved = transactions.update(purchase);
        revisions.save(userId, current, Math.toIntExact(current.version()) + 1, now, REVISION_CHANGE, conversion.describe(reason));
        ledger.deactivateByInstallments(userId, purchase.id(), canceled, CANCEL_REASON, now);
        if (shared) {
            shareSettlement.apply(userId, conversion);
        }
        return assembler.assemble(saved);
    }
}
