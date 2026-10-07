package com.moneylog.core.finance.app.recurrences.usecase;

import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.nio.charset.StandardCharsets;
import java.util.HexFormat;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.finance.app.recurrences.usecase.RealizeCardRecurrenceUseCase.RealizeCardRecurrenceCommand;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.recurrence.app.gateways.RecurrenceGateway;
import com.moneylog.core.recurrence.domain.RecurringTransaction;
import com.moneylog.core.recurrence.exception.RecurrenceExceptions.RecurrenceNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.exception.TransactionExceptions.IdempotencyKeyReusedException;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

/**
 * Realizar uma ocorrência (T80): uma transação com a movimentação e os lançamentos dela. No cartão delega a
 * {@link RealizeCardRecurrenceUseCase} (alocação na fatura com lançamento tardio); em conta, a
 * {@code RecurringTransaction.realize}. A ocorrência fica "realizada" por existir a movimentação ativa com o par
 * {@code (recurrenceId, occurrenceDate)}; estornar a movimentação a reabre (S-49).
 */
public class RealizeOccurrenceUseCaseImpl implements RealizeOccurrenceUseCase {

    /** Validade da chave (S-72), a mesma da criação de movimentação. */
    private static final Duration IDEMPOTENCY_VALIDITY = Duration.ofHours(24);

    private final RecurrenceGateway recurrences;
    private final AccountGateway accounts;
    private final CategoryGateway categories;
    private final TransactionGateway transactions;
    private final LedgerWriteGateway ledger;
    private final TransactionDetailsAssembler assembler;
    private final IdempotencyGateway idempotency;
    private final RealizeCardRecurrenceUseCase cardRealization;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public RealizeOccurrenceUseCaseImpl(RecurrenceGateway recurrences, AccountGateway accounts, CategoryGateway categories,
                                        TransactionGateway transactions, LedgerWriteGateway ledger, TransactionDetailsAssembler assembler,
                                        IdempotencyGateway idempotency, RealizeCardRecurrenceUseCase cardRealization, IdGenerator ids,
                                        TimeProvider time) {
        this.recurrences = recurrences;
        this.accounts = accounts;
        this.categories = categories;
        this.transactions = transactions;
        this.ledger = ledger;
        this.assembler = assembler;
        this.idempotency = idempotency;
        this.cardRealization = cardRealization;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public TransactionDetails handle(RealizeOccurrenceCommand command) {
        UUID userId = command.userId();
        UUID transactionId = ids.next();
        if (command.idempotencyKey() != null) {
            Claim existing = claimOrFind(command, transactionId);
            if (existing != null) {
                return replay(command, existing);
            }
        }
        // ordem global de travas: movimentações (nenhuma) → recorrência → cartão → contas; tudo lido só depois das travas
        recurrences.lockForUpdate(userId, command.recurrenceId());
        RecurringTransaction recurrence = recurrences.findById(userId, command.recurrenceId())
                .orElseThrow(() -> new RecurrenceNotFoundException(command.recurrenceId()));
        if (recurrence.cardId() != null) {
            return cardRealization.handle(new RealizeCardRecurrenceCommand(userId, command.recurrenceId(), command.occurrenceDate(),
                    command.actualAmount(), transactionId));
        }
        UUID accountId = recurrence.accountId();
        accounts.lockForUpdate(userId, accountId);
        Account account = accounts.findById(userId, accountId).map(Persisted::value).orElseThrow(() -> new AccountNotFoundException(accountId));
        Category category = categories.findById(userId, recurrence.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(recurrence.categoryId()));
        Transaction transaction = recurrence.realize(command.occurrenceDate(), transactionId, command.actualAmount(), account, category);
        Persisted<Transaction> saved = transactions.insert(transaction);
        ledger.append(userId, transaction.entries());
        return assembler.assemble(saved);
    }

    /** {@code null} = a chave é nossa (primeira vez ou vencida); senão, o que ela guardou. */
    private Claim claimOrFind(RealizeOccurrenceCommand command, UUID resourceId) {
        String hash = hashOf(command);
        Instant now = time.now();
        for (int attempt = 0; attempt < 3; attempt++) {
            if (idempotency.claim(command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash, resourceId, now,
                    now.minus(IDEMPOTENCY_VALIDITY))) {
                return null;
            }
            var found = idempotency.find(command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey());
            if (found.isPresent()) {
                return found.get();
            }
        }
        throw new IllegalStateException("não foi possível reivindicar nem ler a chave de idempotência");
    }

    private TransactionDetails replay(RealizeOccurrenceCommand command, Claim existing) {
        if (!existing.requestHash().equals(hashOf(command))) {
            throw new IdempotencyKeyReusedException();
        }
        // o replay devolve o estado ATUAL do recurso (S-87), não uma cópia da primeira resposta
        return transactions.findById(command.userId(), existing.resourceId()).map(assembler::assemble)
                .orElseThrow(() -> new TransactionNotFoundException(existing.resourceId()));
    }

    /** Hash do comando canônico: a recorrência, a data e o valor (ausente = o do modelo). O dono não entra (a chave é por usuário). */
    private static String hashOf(RealizeOccurrenceCommand command) {
        String canonical = "recurrenceId=" + command.recurrenceId() + ";date=" + command.occurrenceDate() + ";amount="
                + (command.actualAmount() == null ? "-" : command.actualAmount().toPlainString()) + ";";
        try {
            return HexFormat.of().formatHex(MessageDigest.getInstance("SHA-256").digest(canonical.getBytes(StandardCharsets.UTF_8)));
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 indisponível", e);
        }
    }
}
