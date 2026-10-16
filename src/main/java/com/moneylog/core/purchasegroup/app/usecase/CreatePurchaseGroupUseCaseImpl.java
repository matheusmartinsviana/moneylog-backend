package com.moneylog.core.purchasegroup.app.usecase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.card.domain.InstallmentPlan;
import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase;
import com.moneylog.core.finance.app.cards.usecase.CreateOngoingInstallmentsUseCase.CreateOngoingInstallmentsCommand;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.CardPurchase;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionCommand.Common;
import com.moneylog.core.finance.app.transactions.usecase.CreateTransactionUseCase;
import com.moneylog.core.purchasegroup.app.PurchaseGroupView;
import com.moneylog.core.purchasegroup.app.PurchaseGroupViews;
import com.moneylog.core.purchasegroup.app.gateways.PurchaseGroupGateway;
import com.moneylog.core.purchasegroup.domain.PurchaseGroup;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupInvalidException;
import com.moneylog.core.purchasegroup.exception.PurchaseGroupExceptions.PurchaseGroupNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreatePurchaseGroupUseCaseImpl implements CreatePurchaseGroupUseCase {

    private final PurchaseGroupGateway groups;
    private final CreateTransactionUseCase createPurchase;
    private final CreateOngoingInstallmentsUseCase createOngoing;
    private final FinancialRecordsGateway records;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public CreatePurchaseGroupUseCaseImpl(PurchaseGroupGateway groups, CreateTransactionUseCase createPurchase,
                                          CreateOngoingInstallmentsUseCase createOngoing, FinancialRecordsGateway records,
                                          IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.groups = groups;
        this.createPurchase = createPurchase;
        this.createOngoing = createOngoing;
        this.records = records;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public PurchaseGroupView handle(CreatePurchaseGroupCommand command) {
        UUID groupId = ids.next();
        if (command.idempotencyKey() != null) {
            String hash = IdempotentClaim.hash(command.name() + "|" + command.interest() + "|" + command.declaredTotal() + "|"
                    + command.members());
            Claim existing = IdempotentClaim.claimOrFind(idempotency, command.userId(), IDEMPOTENCY_SCOPE, command.idempotencyKey(), hash,
                    groupId, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                Versioned<PurchaseGroup> stored = groups.findById(command.userId(), existing.resourceId())
                        .orElseThrow(() -> new PurchaseGroupNotFoundException(existing.resourceId()));
                return PurchaseGroupViews.of(stored, records.load(command.userId()), time.today());
            }
        }
        if (command.members().isEmpty() || command.members().size() > PurchaseGroup.MAX_MEMBERS) {
            throw new PurchaseGroupInvalidException("informe de 1 a " + PurchaseGroup.MAX_MEMBERS + " compras.");
        }
        List<UUID> transactionIds = new ArrayList<>();
        for (MemberSpec member : command.members()) {
            transactionIds.add(createMember(command, member));
        }
        PurchaseGroup group = PurchaseGroup.create(groupId, command.userId(), command.name(), command.interest(), command.declaredTotal(),
                transactionIds);
        Versioned<PurchaseGroup> stored = groups.insert(group);
        return PurchaseGroupViews.of(stored, records.load(command.userId()), time.today());
    }

    private UUID createMember(CreatePurchaseGroupCommand group, MemberSpec m) {
        requireValid(m);
        boolean ongoing = m.remainingInstallments() != null && m.remainingInstallments() < m.totalInstallments();
        if (ongoing) {
            if (!m.shares().isEmpty() || m.splitMode() != null) {
                throw new PurchaseGroupInvalidException("a compra em andamento não aceita rateio.");
            }
            return createOngoing.handle(new CreateOngoingInstallmentsCommand(group.userId(), m.cardId(), group.name(), m.installmentAmount(),
                    m.totalInstallments(), m.remainingInstallments(), m.firstInvoiceDate(), m.categoryId())).transaction().id();
        }
        if (m.categoryId() == null) {
            throw new PurchaseGroupInvalidException("a compra comum precisa de categoria.");
        }
        CardPurchase purchase = new CardPurchase(new Common(group.userId(), m.installmentAmount().times(m.totalInstallments()),
                m.firstInvoiceDate(), group.name(), null), m.cardId(), m.categoryId(), m.totalInstallments(), m.splitMode(), m.shares());
        return createPurchase.handle((CreateTransactionCommand) purchase).transaction().id();
    }

    private static void requireValid(MemberSpec m) {
        if (m.cardId() == null || m.installmentAmount() == null || m.firstInvoiceDate() == null) {
            throw new PurchaseGroupInvalidException("cartão, valor da parcela e data da primeira fatura são obrigatórios.");
        }
        if (!m.installmentAmount().isPositive()) {
            throw new PurchaseGroupInvalidException("o valor da parcela precisa ser maior que zero.");
        }
        if (m.totalInstallments() < 2 || m.totalInstallments() > InstallmentPlan.MAX_INSTALLMENTS) {
            throw new PurchaseGroupInvalidException("as parcelas vão de 2 a " + InstallmentPlan.MAX_INSTALLMENTS + ".");
        }
        if (m.remainingInstallments() != null && (m.remainingInstallments() < 1 || m.remainingInstallments() > m.totalInstallments())) {
            throw new PurchaseGroupInvalidException("as parcelas restantes vão de 1 ao total de parcelas.");
        }
    }
}
