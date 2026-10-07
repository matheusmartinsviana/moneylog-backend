package com.moneylog.core.finance.app.transactions.usecase;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.transactions.handlers.BuiltTransaction;
import com.moneylog.core.finance.app.transactions.AccountTransactionFactory;
import com.moneylog.core.finance.app.transactions.CanonicalCommandHash;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionOrigin;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class CreateTransactionUseCaseImpl implements CreateTransactionUseCase {

    /** Validade da chave (S-72): depois disso ela é sobrescrita pela próxima criação. */
    public static final Duration IDEMPOTENCY_VALIDITY = Duration.ofHours(24);

    private final TransactionGateway transactions;
    private final AccountTransactionFactory factory;
    private final LedgerWriteGateway ledger;
    private final IdGenerator ids;
    private final TransactionDetailsAssembler assembler;
    private final IdempotencyGateway idempotency;
    private final TimeProvider time;
    private final DebtGateway debts;

    /** Sem dívidas (Etapas 2 e 3). */
    public CreateTransactionUseCaseImpl(TransactionGateway transactions, AccountTransactionFactory factory,
                                        LedgerWriteGateway ledger, IdGenerator ids, TransactionDetailsAssembler assembler,
                                        IdempotencyGateway idempotency, TimeProvider time) {
        this(transactions, factory, ledger, ids, assembler, idempotency, time, null);
    }

    /** Com dívidas (Etapa 4): as dívidas que nascem com a movimentação são gravadas antes dela. */
    @SuppressWarnings("java:S107")
    public CreateTransactionUseCaseImpl(TransactionGateway transactions, AccountTransactionFactory factory,
                                        LedgerWriteGateway ledger, IdGenerator ids, TransactionDetailsAssembler assembler,
                                        IdempotencyGateway idempotency, TimeProvider time, DebtGateway debts) {
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

    private TransactionDetails create(CreateTransactionCommand command, UUID id, TransactionOrigin origin) {
        BuiltTransaction built = factory.buildWithEffects(command, id, origin, null);
        Transaction transaction = built.transaction();
        // a dívida que nasce com a movimentação entra antes dela (a movimentação aponta para a dívida; a FK da origem é diferida)
        for (Debt debt : built.newDebts()) {
            if (debts == null) {
                throw new IllegalStateException("a movimentação cria dívida mas o caso de uso não tem a porta de dívidas");
            }
            debts.insert(debt);
        }
        Persisted<Transaction> saved = transactions.insert(transaction);
        ledger.append(command.userId(), transaction.entries());
        return assembler.assemble(saved);
    }

    /** {@code null} = a chave é nossa (primeira vez ou vencida); senão, o que ela guardou. */
    private Claim claimOrFind(CreateTransactionCommand command, UUID key, UUID resourceId) {
        String hash = CanonicalCommandHash.of(command);
        Instant now = time.now();
        // a chave some entre o claim recusado e o find só se a transação que a segurava deu rollback: tenta de novo
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
        // o replay devolve o estado ATUAL do recurso (S-87), não uma cópia da primeira resposta
        return transactions.findById(command.userId(), existing.resourceId()).map(assembler::assemble)
                .orElseThrow(() -> new TransactionNotFoundException(existing.resourceId()));
    }
}
