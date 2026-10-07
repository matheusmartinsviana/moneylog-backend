package com.moneylog.core.finance.app.debts.usecase;

import java.util.UUID;

import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.category.domain.Category;
import com.moneylog.core.category.exception.CategoryExceptions.CategoryNotFoundException;
import com.moneylog.core.debt.app.DebtView;
import com.moneylog.core.debt.app.DebtViews;
import com.moneylog.core.debt.app.gateways.DebtGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.domain.Debt;
import com.moneylog.core.debt.domain.Person;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.finance.app.gateways.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.debt.exception.DebtExceptions.DebtNotFoundException;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateDebtUseCaseImpl implements CreateDebtUseCase {

    private final PersonGateway people;
    private final DebtGateway debts;
    private final CategoryGateway categories;
    private final LedgerWriteGateway ledger;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public CreateDebtUseCaseImpl(PersonGateway people, DebtGateway debts, CategoryGateway categories, LedgerWriteGateway ledger,
                                 IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.idempotency = idempotency;
        this.people = people;
        this.debts = debts;
        this.categories = categories;
        this.ledger = ledger;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public DebtView handle(CreateDebtCommand command) {
        UUID debtId = ids.next();
        if (command.idempotencyKey() != null) {
            Claim existing = claimOrFind(command, debtId);
            if (existing != null) {
                return replay(command, existing);
            }
        }
        if (command.creditorName() != null || command.personId() == null) {
            return createWithFreeCreditor(command, debtId);
        }
        // arquivar a pessoa e criar dívida para ela se serializam
        people.lockForUpdate(command.userId(), command.personId());
        Person person = people.findById(command.userId(), command.personId()).map(Versioned::value)
                .orElseThrow(() -> new PersonNotFoundException(command.personId()));
        Category category = command.categoryId() == null ? null : categories.findById(command.userId(), command.categoryId())
                .orElseThrow(() -> new CategoryNotFoundException(command.categoryId()));
        Debt debt = Debt.withoutMovement(debtId, command.userId(), person, command.direction(), command.principal(), command.date(),
                command.expectedDate(), command.description(), category);
        Versioned<Debt> saved = debts.insert(debt);
        ledger.append(command.userId(), debt.entries());
        return DebtViews.one(people, debts, categories, command.userId(), saved, time.today());
    }

    /** "Outra dívida" (P1): credor livre, sem pessoa e sem categoria; contra EQUITY como a dívida sem movimentação. */
    private DebtView createWithFreeCreditor(CreateDebtCommand command, UUID debtId) {
        if (command.personId() != null) {
            throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException("informe a pessoa ou o nome do credor, não os dois.");
        }
        if (command.creditorName() == null || command.creditorName().isBlank()) {
            throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException("informe a pessoa ou o nome do credor.");
        }
        if (command.direction() != com.moneylog.core.debt.domain.DebtDirection.PAYABLE) {
            throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException("o credor livre só existe em dívida que você deve (PAYABLE).");
        }
        if (command.categoryId() != null) {
            throw new com.moneylog.core.debt.exception.DebtExceptions.DebtCreditorInvalidException("a dívida com credor livre não tem categoria.");
        }
        Debt debt = Debt.withoutMovementToCreditor(debtId, command.userId(), command.creditorName(), command.principal(), command.date(),
                command.expectedDate(), command.description());
        Versioned<Debt> saved = debts.insert(debt);
        ledger.append(command.userId(), debt.entries());
        return DebtViews.one(people, debts, categories, command.userId(), saved, time.today());
    }

    private Claim claimOrFind(CreateDebtCommand command, UUID resourceId) {
        return IdempotentClaim.claimOrFind(idempotency, command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hashOf(command),
                resourceId, time.now());
    }

    private DebtView replay(CreateDebtCommand command, Claim existing) {
        IdempotentClaim.requireSameCommand(existing, hashOf(command));
        // o replay devolve o estado ATUAL da dívida (S-87), não uma cópia da primeira resposta
        Versioned<Debt> debt = debts.findById(command.userId(), existing.resourceId())
                .orElseThrow(() -> new DebtNotFoundException(existing.resourceId()));
        return DebtViews.one(people, debts, categories, command.userId(), debt, time.today());
    }

    /** Hash do comando canônico (o dono não entra: a chave é por usuário). */
    private static String hashOf(CreateDebtCommand c) {
        return IdempotentClaim.hash("personId=" + c.personId() + ";creditor=" + (c.creditorName() == null ? "-" : c.creditorName().strip()) + ";direction=" + c.direction() + ";principal=" + c.principal().toPlainString()
                + ";date=" + c.date() + ";expectedDate=" + (c.expectedDate() == null ? "-" : c.expectedDate())
                + ";description=" + (c.description() == null ? "-" : c.description().strip()) + ";categoryId="
                + (c.categoryId() == null ? "-" : c.categoryId()) + ";");
    }
}
