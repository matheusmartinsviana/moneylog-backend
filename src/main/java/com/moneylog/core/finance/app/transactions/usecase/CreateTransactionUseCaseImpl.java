package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.handlers.BuiltTransaction;
import com.moneylog.core.finance.app.transactions.AccountTransactionFactory;
import com.moneylog.core.finance.app.transactions.CanonicalCommandHash;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.ForeignChargeGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class CreateTransactionUseCaseImpl implements CreateTransactionUseCase {

    public static final Duration IDEMPOTENCY_VALIDITY = Duration.ofHours(24);

    private final TransactionGateway transactions;
    private final AccountTransactionFactory factory;
    private final LedgerWriteGateway ledger;
    private final IdGenerator ids;
    private final TransactionDetailsAssembler assembler;
    private final IdempotencyGateway idempotency;
    private final TimeProvider time;
    private final DebtGateway debts;
    private final ForeignChargeGateway foreignCharges;

    public CreateTransactionUseCaseImpl(TransactionGateway transactions, AccountTransactionFactory factory,
                                        LedgerWriteGateway ledger, IdGenerator ids, TransactionDetailsAssembler assembler,
                                        IdempotencyGateway idempotency, TimeProvider time) {
        this(transactions, factory, ledger, ids, assembler, idempotency, time, null);
    }

    @SuppressWarnings("java:S107")
    public CreateTransactionUseCaseImpl(TransactionGateway transactions, AccountTransactionFactory factory,
                                        LedgerWriteGateway ledger, IdGenerator ids, TransactionDetailsAssembler assembler,
                                        IdempotencyGateway idempotency, TimeProvider time, DebtGateway debts) {
        this(transactions, factory, ledger, ids, assembler, idempotency, time, debts, null);
    }

    @SuppressWarnings("java:S107")
    public CreateTransactionUseCaseImpl(TransactionGateway transactions, AccountTransactionFactory factory,
                                        LedgerWriteGateway ledger, IdGenerator ids, TransactionDetailsAssembler assembler,
                                        IdempotencyGateway idempotency, TimeProvider time, DebtGateway debts,
                                        ForeignChargeGateway foreignCharges) {
        this.foreignCharges = foreignCharges;
        this.debts = debts;
        this.transactions = transactions;
        this.factory = factory;
        this.ledger = ledger;
        this.ids = ids;
        this.assembler = assembler;
        this.idempotency = idempotency;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(CreateTransactionCommand command, UUID idempotencyKey) {
        UUID id = ids.next();
        if (idempotencyKey != null) {
            Claim existing = claimOrFind(command, idempotencyKey, id);
            if (existing != null) {
                return replay(command, existing);
            }
        }
        return create(command, id, TransactionOrigin.manual());
    }

    @Override
    public TransactionDetails handleImported(CreateTransactionCommand command, TransactionOrigin origin) {
        if (origin.source() != com.moneylog.core.transaction.domain.TransactionSource.IMPORT) {
            throw new IllegalArgumentException("handleImported exige a origem IMPORT");
        }
        return create(command, ids.next(), origin);
    }

    @Override
    public TransactionDetails handleClaimed(CreateTransactionCommand command, UUID id, TransactionOrigin origin) {
        return create(command, id, origin);
    }

    private TransactionDetails create(CreateTransactionCommand command, UUID id, TransactionOrigin origin) {
        BuiltTransaction built = factory.buildWithEffects(command, id, origin, null);
        Transaction transaction = built.transaction();
        for (Debt debt : built.newDebts()) {
            if (debts == null) {
                throw new IllegalStateException("a movimentação cria dívida mas o caso de uso não tem a porta de dívidas");
            }
            debts.insert(debt);
        }
        Persisted<Transaction> saved = transactions.insert(transaction);
        if (transaction.foreignCharge() != null) {
            foreignCharges.save(command.userId(), transaction.id(), transaction.foreignCharge());
        }
        ledger.append(command.userId(), transaction.entries());
        if (!built.settlementEntries().isEmpty()) {
            ledger.append(command.userId(), built.settlementEntries());
        }
        return assembler.assemble(saved);
    }

    private Claim claimOrFind(CreateTransactionCommand command, UUID key, UUID resourceId) {
        String hash = CanonicalCommandHash.of(command);
        Instant now = time.now();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (idempotency.claim(command.userId(), IDEMPOTENCY_SCOPE, key, hash, resourceId, now, now.minus(IDEMPOTENCY_VALIDITY))) {
                return null;
            }
            var found = idempotency.find(command.userId(), IDEMPOTENCY_SCOPE, key);
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalStateException("não foi possível reivindicar nem ler a chave de idempotência");
    }

    private TransactionDetails replay(CreateTransactionCommand command, Claim existing) {
        if (!existing.requestHash().equals(CanonicalCommandHash.of(command))) {
            throw new IdempotencyKeyReusedException();
        }
        return transactions.findById(command.userId(), existing.resourceId()).map(assembler::assemble)
                .orElseThrow(() -> new TransactionNotFoundException(existing.resourceId()));
    }
}
