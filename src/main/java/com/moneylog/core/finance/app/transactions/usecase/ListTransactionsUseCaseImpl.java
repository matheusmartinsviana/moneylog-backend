package com.moneylog.core.finance.app.transactions.usecase;

import java.time.LocalDate;
import java.util.List;

import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.finance.app.transactions.TransactionListing;
import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.finance.domain.FinancialRecords;
import com.moneylog.core.finance.domain.Ledger;
import com.moneylog.core.sk.page.Slice;
import com.moneylog.core.sk.persistence.Persisted;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.Transaction;
import com.moneylog.core.transaction.domain.TransactionStatus;

public class ListTransactionsUseCaseImpl implements ListTransactionsUseCase {

    private final TransactionGateway transactions;
    private final FinancialRecordsGateway records;
    private final TransactionDetailsAssembler assembler;

    public ListTransactionsUseCaseImpl(TransactionGateway transactions, FinancialRecordsGateway records,
                                       TransactionDetailsAssembler assembler) {
        this.transactions = transactions;
        this.records = records;
        this.assembler = assembler;
    }

    @Override
    public TransactionListing handle(ListTransactionsQuery query) {
        TransactionFilter filter = query.filter();
        FinancialRecords loaded = records.load(query.userId());
        Ledger ledger = loaded.ledger();
        LocalDate from = filter.from() == null ? LocalDate.MIN : filter.from();
        LocalDate to = filter.to() == null ? LocalDate.MAX : filter.to();
        CashFlowReport cashFlow = filter.accountId() == null
                ? CashFlowReport.between(loaded, ledger, from, to, filter.attributes())
                : CashFlowReport.forAccount(loaded, ledger, filter.accountId(), from, to, filter.attributes());
        int count = filter.status() == TransactionStatus.VOIDED ? 0
                : (int) loaded.transactions().stream().filter(Transaction::isActive).filter(filter::matches).count();

        Slice<Persisted<Transaction>> page = transactions.list(query.userId(), filter, query.page());
        List<TransactionDetails> items = assembler.assembleAll(query.userId(), page.items(), loaded);
        return new TransactionListing(new Slice<>(items, page.hasNext()), count, cashFlow);
    }
}
