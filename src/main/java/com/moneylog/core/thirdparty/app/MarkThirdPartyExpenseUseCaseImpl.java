package com.moneylog.core.thirdparty.app;

import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyExpense;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseAlreadyMarkedException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseInvalidException;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class MarkThirdPartyExpenseUseCaseImpl implements MarkThirdPartyExpenseUseCase {

    private final ThirdPartyExpenseGateway marks;
    private final TransactionGateway transactions;
    private final PersonGateway people;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    public MarkThirdPartyExpenseUseCaseImpl(ThirdPartyExpenseGateway marks, TransactionGateway transactions, PersonGateway people,
                                            IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.marks = marks;
        this.transactions = transactions;
        this.people = people;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public ThirdPartyExpense handle(MarkThirdPartyExpenseCommand c) {
        UUID id = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = IdempotentClaim.hash("tx=" + c.transactionId() + ";person=" + c.personId() + ";note=" + (c.note() == null ? "-" : c.note()) + ";amount=" + (c.amount() == null ? "-" : c.amount().toPlainString()) + ";");
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, id, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                return marks.findByTransaction(c.userId(), c.transactionId()).orElseThrow(ThirdPartyExpenseAlreadyMarkedException::new);
            }
        }
        Transaction tx = transactions.findById(c.userId(), c.transactionId()).map(p -> p.value())
                .orElseThrow(() -> new TransactionNotFoundException(c.transactionId()));
        if (!tx.isActive() || (tx.type() != TransactionType.EXPENSE && tx.type() != TransactionType.CARD_PURCHASE)) {
            throw new ThirdPartyExpenseInvalidException("só uma despesa ou compra no cartão ativa pode ser de terceiro.");
        }
        if (c.amount() != null && (!c.amount().isPositive() || c.amount().isGreaterThan(tx.chargedAmount()))) {
            throw new ThirdPartyExpenseInvalidException("a parte reembolsável precisa estar entre 0,01 e o valor cobrado da compra.");
        }
        var person = people.findById(c.userId(), c.personId()).map(v -> v.value()).orElseThrow(() -> new PersonNotFoundException(c.personId()));
        person.ensureCanReceiveDebts();
        if (marks.findByTransaction(c.userId(), c.transactionId()).isPresent()) {
            throw new ThirdPartyExpenseAlreadyMarkedException();
        }
        return marks.insert(new ThirdPartyExpense(id, c.userId(), c.transactionId(), c.personId(), c.note(), c.amount()));
    }
}
