package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.ShareSettlement;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.sk.exception.VersionConflictException;
import com.moneylog.core.sk.ledger.LedgerEntry;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.gateways.TransactionRevisionGateway;
import com.moneylog.core.transaction.domain.CardTransactions;
import com.moneylog.core.transaction.domain.ShareLine;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.exception.TransactionExceptions.SharePersonArchivedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotEditableException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class UpdateTransactionSharesUseCaseImpl implements UpdateTransactionSharesUseCase {

    private final TransactionGateway transactions;
    private final PersonGateway persons;
    private final ShareSettlement shareSettlement;
    private final TransactionRevisionGateway revisions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public UpdateTransactionSharesUseCaseImpl(TransactionGateway transactions, PersonGateway persons, ShareSettlement shareSettlement,
                                              TransactionRevisionGateway revisions, LedgerWriteGateway ledger,
                                              TransactionDetailsAssembler assembler, TimeProvider time) {
        this.transactions = transactions;
        this.persons = persons;
        this.shareSettlement = shareSettlement;
        this.revisions = revisions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(UpdateTransactionSharesCommand command) {
        UUID userId = command.userId();
        transactions.lockForUpdate(userId, command.transactionId());
        Transaction current = transactions.findById(userId, command.transactionId()).map(Persisted::value)
                .orElseThrow(() -> new TransactionNotFoundException(command.transactionId()));
        List<UUID> currentPersons = current.purchaseShares() == null ? List.of() : current.purchaseShares().personIds();
        List<UUID> requested = command.shares().stream().map(ShareLine::personId).filter(java.util.Objects::nonNull).toList();
        List<UUID> toLock = new ArrayList<>(currentPersons);
        toLock.addAll(requested);
        shareSettlement.lockPersons(userId, toLock);
        if (current.version() != command.version()) {
            throw new VersionConflictException(command.version(), current.version());
        }
        requireUsableNewPersons(userId, currentPersons, requested);
        if (!transactions.findActiveRefundsOf(userId, current.id()).isEmpty()) {
            throw new TransactionNotEditableException("a compra tem estorno ativo; estorne o estorno antes de mudar o rateio.");
        }
        java.util.Map<UUID, Money> received = netReceived(userId, current.id());
        Transaction replacement = CardTransactions.reshare(current, command.splitMode(), command.shares(), received);
        Instant now = time.now();
        Persisted<Transaction> saved = transactions.update(replacement);
        revisions.save(userId, current, Math.toIntExact(current.version()) + 1, now, REVISION_CHANGE, null);
        ledger.deactivateBySource(userId, PostingSourceType.TRANSACTION, current.id(), UpdateTransactionUseCase.EDIT_REASON, now);
        ledger.append(userId, replacement.entries().stream().filter(LedgerEntry::active).toList());
        return assembler.assemble(saved);
    }

    private java.util.Map<UUID, Money> netReceived(UUID userId, UUID transactionId) {
        java.util.Map<UUID, Money> converted = shareSettlement.convertedOn(userId, transactionId);
        java.util.Map<UUID, Money> net = new java.util.TreeMap<>();
        shareSettlement.receivedOn(userId, transactionId).forEach((personId, amount) -> {
            Money value = amount.subtract(converted.getOrDefault(personId, Money.zero(amount.currency())));
            if (value.isPositive()) {
                net.put(personId, value);
            }
        });
        return net;
    }

    private void requireUsableNewPersons(UUID userId, List<UUID> currentPersons, List<UUID> requested) {
        for (UUID personId : requested.stream().distinct().toList()) {
            Person person = persons.findById(userId, personId).map(v -> v.value()).orElseThrow(() -> new PersonNotFoundException(personId));
            if (person.isArchived() && !currentPersons.contains(personId)) {
                throw new SharePersonArchivedException();
            }
        }
    }
}
