package com.moneylog.core.thirdparty.app;

import java.time.LocalDate;
import java.util.UUID;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.account.domain.Account;
import com.moneylog.core.account.domain.LiquidityClass;
import com.moneylog.core.account.exception.AccountExceptions.AccountNotFoundException;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.debt.exception.DebtExceptions.PersonNotFoundException;
import com.moneylog.core.debt.exception.DebtExceptions.SettlementExceedsOutstandingException;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.ledger.LedgerReadGateway;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.money.CurrencyCode;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.sk.time.FinancialDates;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.IdempotencyKeyRequiredException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseInvalidException;
import com.moneylog.core.transaction.app.IdempotentClaim;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway;
import com.moneylog.core.transaction.app.gateways.IdempotencyGateway.Claim;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.SharePayables;
import com.moneylog.core.transaction.domain.Transaction;

public class RecordThirdPartyReceiptUseCaseImpl implements RecordThirdPartyReceiptUseCase {

    private final ThirdPartyExpenseGateway marks;
    private final TransactionGateway transactions;
    private final PersonGateway people;
    private final AccountGateway accounts;
    private final LedgerWriteGateway ledger;
    private final LedgerReadGateway ledgerRead;
    private final IdempotencyGateway idempotency;
    private final IdGenerator ids;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public RecordThirdPartyReceiptUseCaseImpl(ThirdPartyExpenseGateway marks, TransactionGateway transactions, PersonGateway people,
                                              AccountGateway accounts, LedgerWriteGateway ledger, IdempotencyGateway idempotency,
                                              IdGenerator ids, TimeProvider time, LedgerReadGateway ledgerRead) {
        this.ledgerRead = ledgerRead;
        this.marks = marks;
        this.transactions = transactions;
        this.people = people;
        this.accounts = accounts;
        this.ledger = ledger;
        this.idempotency = idempotency;
        this.ids = ids;
        this.time = time;
    }

    @Override
    public ThirdPartyReceipt handle(RecordThirdPartyReceiptCommand c) {
        UUID id = ids.next();
        if (c.idempotencyKey() != null) {
            String hash = IdempotentClaim.hash("person=" + c.personId() + ";tx=" + c.transactionId() + ";amount=" + (c.amount() == null ? "-" : c.amount().toPlainString())
                    + ";on=" + c.receivedOn() + ";note=" + (c.note() == null ? "-" : c.note()) + ";account=" + c.accountId() + ";");
            Claim existing = IdempotentClaim.claimOrFind(idempotency, c.userId(), IDEMPOTENCY_SCOPE, c.idempotencyKey(), hash, id, time.now());
            if (existing != null) {
                IdempotentClaim.requireSameCommand(existing, hash);
                return marks.findReceipt(c.userId(), existing.resourceId())
                        .orElseThrow(() -> new ThirdPartyExpenseInvalidException("registro não encontrado."));
            }
        }
        FinancialDates.requireInWindow(c.receivedOn(), "receivedOn");
        if (c.transactionId() == null) {
            requireNoAccount(c);
            people.findById(c.userId(), c.personId()).orElseThrow(() -> new PersonNotFoundException(c.personId()));
            return marks.insertReceipt(new ThirdPartyReceipt(id, c.userId(), c.personId(), null, c.amount(), c.receivedOn(), c.note()));
        }
        transactions.lockForUpdate(c.userId(), c.transactionId());
        Transaction tx = transactions.findById(c.userId(), c.transactionId()).map(Persisted::value).orElse(null);
        if (tx != null && tx.purchaseShares() != null) {
            return recordOnShare(id, c, tx);
        }
        requireNoAccount(c);
        people.findById(c.userId(), c.personId()).orElseThrow(() -> new PersonNotFoundException(c.personId()));
        var mark = marks.findByTransaction(c.userId(), c.transactionId())
                .orElseThrow(() -> new ThirdPartyExpenseInvalidException("o recebimento só se liga a uma despesa marcada como de terceiro ou a uma compra rateada."));
        if (!mark.personId().equals(c.personId())) {
            throw new ThirdPartyExpenseInvalidException("o recebimento é de outra pessoa que a da marca da despesa.");
        }
        return marks.insertReceipt(new ThirdPartyReceipt(id, c.userId(), c.personId(), c.transactionId(), c.amount(), c.receivedOn(), c.note()));
    }

    private ThirdPartyReceipt recordOnShare(UUID id, RecordThirdPartyReceiptCommand c, Transaction tx) {
        if (c.accountId() == null && c.idempotencyKey() == null) {
            throw new IdempotencyKeyRequiredException();
        }
        people.lockForUpdate(c.userId(), c.personId());
        people.findById(c.userId(), c.personId()).orElseThrow(() -> new PersonNotFoundException(c.personId()));
        if (!tx.isActive()) {
            throw new ThirdPartyExpenseInvalidException("a compra rateada não está ativa.");
        }
        Money zero = Money.zero(CurrencyCode.BRL);
        Money owed = SharePayables.owedNet(tx, transactions.findActiveRefundsOf(c.userId(), tx.id())).getOrDefault(c.personId(), zero);
        if (!owed.isPositive()) {
            throw new ThirdPartyExpenseInvalidException("a pessoa não tem parte nesta compra.");
        }
        Money received = Money.sum(marks.findAllReceipts(c.userId()).stream()
                .filter(r -> tx.id().equals(r.transactionId()) && c.personId().equals(r.personId()))
                .map(ThirdPartyReceipt::amount).toList(), CurrencyCode.BRL);
        Money converted = SharePayables.alreadyConverted(tx.id(), ledgerRead.entriesOfSource(c.userId(), PostingSourceType.SHARE_PAYABLE, tx.id()))
                .getOrDefault(c.personId(), zero);
        if (c.amount().isGreaterThan(owed.subtract(received).add(converted))) {
            throw new SettlementExceedsOutstandingException();
        }
        if (c.accountId() != null) {
            accounts.lockForUpdate(c.userId(), c.accountId());
            Account account = accounts.findById(c.userId(), c.accountId()).map(Persisted::value)
                    .orElseThrow(() -> new AccountNotFoundException(c.accountId()));
            account.ensureCanReceiveTransactions();
            if (account.liquidity() != LiquidityClass.CASH) {
                throw new ThirdPartyExpenseInvalidException("o recebimento só entra numa conta de caixa.");
            }
        }
        LocalDate on = c.receivedOn() == null ? time.today() : c.receivedOn();
        ThirdPartyReceipt receipt = ThirdPartyReceipt.onShare(id, c.userId(), c.personId(), tx.id(), c.amount(), on, c.note(), c.accountId());
        marks.insertReceipt(receipt);
        ledger.append(c.userId(), receipt.ledgerEntries());
        return receipt;
    }

    private static void requireNoAccount(RecordThirdPartyReceiptCommand c) {
        if (c.accountId() != null) {
            throw new ThirdPartyExpenseInvalidException("a conta só vale no recebimento de uma compra rateada.");
        }
    }
}
