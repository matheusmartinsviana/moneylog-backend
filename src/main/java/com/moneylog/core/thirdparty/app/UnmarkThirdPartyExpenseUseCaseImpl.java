package com.moneylog.core.thirdparty.app;

import com.moneylog.core.account.app.gateways.AccountGateway;
import com.moneylog.core.debt.app.gateways.PersonGateway;
import com.moneylog.core.sk.ledger.LedgerReadGateway;
import com.moneylog.core.sk.ledger.LedgerWriteGateway;
import com.moneylog.core.sk.ledger.PostingSourceType;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.thirdparty.app.gateways.ThirdPartyExpenseGateway;
import com.moneylog.core.thirdparty.domain.ThirdPartyReceipt;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ReceiptNotRemovableException;
import com.moneylog.core.thirdparty.exception.ThirdPartyExceptions.ThirdPartyExpenseNotFoundException;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.domain.SharePayables;

public class UnmarkThirdPartyExpenseUseCaseImpl implements UnmarkThirdPartyExpenseUseCase {

    private static final String RECEIPT_REMOVED_REASON = "recebimento de terceiro removido";

    private final ThirdPartyExpenseGateway marks;
    private final TransactionGateway transactions;
    private final PersonGateway people;
    private final AccountGateway accounts;
    private final LedgerWriteGateway ledger;
    private final LedgerReadGateway ledgerRead;
    private final TimeProvider time;

    @SuppressWarnings("java:S107")
    public UnmarkThirdPartyExpenseUseCaseImpl(ThirdPartyExpenseGateway marks, TransactionGateway transactions, PersonGateway people,
                                              AccountGateway accounts, LedgerWriteGateway ledger, LedgerReadGateway ledgerRead,
                                              TimeProvider time) {
        this.marks = marks;
        this.transactions = transactions;
        this.people = people;
        this.accounts = accounts;
        this.ledger = ledger;
        this.ledgerRead = ledgerRead;
        this.time = time;
    }

    @Override
    public void deleteReceipt(UnmarkThirdPartyExpenseCommand c) {
        ThirdPartyReceipt probe = marks.findReceipt(c.userId(), c.id()).orElseThrow(() -> new ThirdPartyExpenseNotFoundException(c.id()));
        if (probe.ledgered()) {
            transactions.lockForUpdate(c.userId(), probe.transactionId());
            people.lockForUpdate(c.userId(), probe.personId());
            if (probe.accountId() != null) {
                accounts.lockForUpdate(c.userId(), probe.accountId());
            }
            ThirdPartyReceipt receipt = marks.findReceipt(c.userId(), c.id()).orElseThrow(() -> new ThirdPartyExpenseNotFoundException(c.id()));
            requireRemovable(receipt);
            ledger.deactivateBySource(c.userId(), receipt.ledgerSourceType(), receipt.id(), RECEIPT_REMOVED_REASON, time.now());
        }
        if (!marks.deleteReceipt(c.userId(), c.id())) {
            throw new ThirdPartyExpenseNotFoundException(c.id());
        }
    }

    private void requireRemovable(ThirdPartyReceipt receipt) {
        if (!ledgerRead.entriesOfSource(receipt.userId(), PostingSourceType.THIRD_PARTY_REFUND_ADJUSTMENT, receipt.id()).isEmpty()) {
            throw new ReceiptNotRemovableException("ele vem da migração de um reembolso; apagá-lo recriaria a dupla contagem.");
        }
        var converted = SharePayables.alreadyConverted(receipt.transactionId(),
                ledgerRead.entriesOfSource(receipt.userId(), PostingSourceType.SHARE_PAYABLE, receipt.transactionId()));
        if (converted.containsKey(receipt.personId())) {
            throw new ReceiptNotRemovableException("o excedente dele já virou valor a pagar à pessoa.");
        }
    }

    @Override
    public void handle(UnmarkThirdPartyExpenseCommand c) {
        marks.findById(c.userId(), c.id()).orElseThrow(() -> new ThirdPartyExpenseNotFoundException(c.id()));
        marks.delete(c.userId(), c.id());
    }
}
