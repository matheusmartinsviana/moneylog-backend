package com.moneylog.core.thirdparty.app;

import java.util.UUID;

import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseInvalidException;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;

public class RecordThirdPartyReceiptUseCaseImpl implements RecordThirdPartyReceiptUseCase {

    private final ThirdPartyExpenseGateway marks;
    private final PersonGateway people;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    public RecordThirdPartyReceiptUseCaseImpl(ThirdPartyExpenseGateway marks, PersonGateway people, IdempotencyGateway idempotency,
                                              IdGenerator ids, TimeProvider time) {
        this.marks = marks;
        this.people = people;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public ThirdPartyReceipt handle(RecordThirdPartyReceiptCommand c) {
        UUID id = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = IdempotentClaim.hash("person=" + c.personId() + ";tx=" + c.transactionId() + ";amount=" + (c.amount() == null ? "-" : c.amount().toPlainString())
                    + ";on=" + c.receivedOn() + ";note=" + (c.note() == null ? "-" : c.note()) + ";");
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, id, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                return marks.findAllReceipts(c.userId()).stream().filter(r -> r.id().equals(existing.resourceId())).findFirst()
                        .orElseThrow(() -> new ThirdPartyExpenseInvalidException("registro não encontrado."));
            }
        }
        FinancialDates.requireInWindow(c.receivedOn(), "receivedOn");
        people.findById(c.userId(), c.personId()).orElseThrow(() -> new PersonNotFoundException(c.personId()));
        if (c.transactionId() != null) {
            var mark = marks.findByTransaction(c.userId(), c.transactionId())
                    .orElseThrow(() -> new ThirdPartyExpenseInvalidException("o recebimento só se liga a uma despesa marcada como de terceiro."));
            if (!mark.personId().equals(c.personId())) {
                throw new ThirdPartyExpenseInvalidException("o recebimento é de outra pessoa que a da marca da despesa.");
            }
        }
        return marks.insertReceipt(new ThirdPartyReceipt(id, c.userId(), c.personId(), c.transactionId(), c.amount(), c.receivedOn(), c.note()));
    }
}
