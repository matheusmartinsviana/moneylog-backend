package com.moneylog.core.commitment.app.usecase;

import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import com.moneylog.core.commitment.app.CommitmentView;
import com.moneylog.core.commitment.app.gateways.CommitmentGateway;
import com.moneylog.core.commitment.domain.CommitmentKind;
import com.moneylog.core.commitment.domain.ScheduleItem;
import com.moneylog.core.commitment.domain.TrackedCommitment;
import com.moneylog.core.commitment.exception.CommitmentExceptions.CommitmentNotFoundException;
import com.moneylog.core.commitment.exception.CommitmentExceptions.InvalidCommitmentException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class CreateCommitmentUseCaseImpl implements CreateCommitmentUseCase {

    private final CommitmentGateway commitments;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    public CreateCommitmentUseCaseImpl(CommitmentGateway commitments, IdempotencyGateway idempotency, IdGenerator ids, TimeProvider time) {
        this.commitments = commitments;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public CommitmentView handle(CreateCommitmentCommand c) {
        UUID id = ids.next();
        if (c.idempotencyKey() != null) {
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hashOf(c), id,
                    time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hashOf(c));
                return new CommitmentView(commitments.findById(c.userId(), existing.resourceId())
                        .orElseThrow(() -> new CommitmentNotFoundException(existing.resourceId())));
            }
        }
        if (c.kind() == null) {
            throw new InvalidCommitmentException("o tipo é obrigatório.");
        }
        TrackedCommitment commitment;
        if (c.kind() == CommitmentKind.LONG_TERM_PROJECT) {
            List<ScheduleItem> items = new ArrayList<>();
            int position = 1;
            for (ItemInput i : c.schedule() == null ? List.<ItemInput>of() : c.schedule()) {
                items.add(new ScheduleItem(position++, i.label(), i.type(), i.dueDate(), i.amount(), i.paid(), i.paidOn()));
            }
            commitment = TrackedCommitment.longTermProject(id, c.userId(), c.name(), c.counterparty(), c.notes(), c.total(), items);
        } else {
            if (c.installmentsTotal() == null || c.installmentsPaid() == null) {
                throw new InvalidCommitmentException("informe parcelas pagas e total de parcelas.");
            }
            commitment = TrackedCommitment.trackedThirdParty(id, c.userId(), c.name(), c.counterparty(), c.notes(),
                    c.installmentsTotal(), c.installmentsPaid(), c.paidAmount(), c.nextDueDate());
        }
        return new CommitmentView(commitments.insert(commitment));
    }

    private static String hashOf(CreateCommitmentCommand c) {
        StringBuilder sb = new StringBuilder("kind=" + c.kind() + ";name=" + nz(c.name()).strip() + ";cp=" + nz(c.counterparty()) + ";notes="
                + nz(c.notes()) + ";total=" + (c.total() == null ? "-" : c.total().toPlainString()) + ";it=" + nz(c.installmentsTotal())
                + ";ip=" + nz(c.installmentsPaid()) + ";paid=" + (c.paidAmount() == null ? "-" : c.paidAmount().toPlainString())
                + ";next=" + nz(c.nextDueDate()) + ";");
        if (c.schedule() != null) {
            c.schedule().forEach(i -> sb.append(i.label()).append('|').append(i.type()).append('|').append(nz(i.dueDate())).append('|').append(i.paid()).append('|')
                    .append(i.amount() == null ? "-" : i.amount().toPlainString()).append('|').append(nz(i.paidOn())).append(';'));
        }
        return IdempotentClaim.hash(sb.toString());
    }

    private static String nz(Object value) {
        return value == null ? "-" : value.toString();
    }
}
