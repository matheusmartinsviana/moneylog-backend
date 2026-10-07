package com.moneylog.core.finance.app.transactions.usecase;

import com.moneylog.core.finance.app.transactions.TransactionDetails;
import com.moneylog.core.finance.app.transactions.TransactionDetailsAssembler;
import com.moneylog.core.transaction.app.gateways.TransactionGateway;
import com.moneylog.core.transaction.exception.TransactionExceptions.TransactionNotFoundException;

public class GetTransactionUseCaseImpl implements GetTransactionUseCase {

    private final TransactionGateway transactions;
    private final TransactionDetailsAssembler assembler;

    public GetTransactionUseCaseImpl(TransactionGateway transactions, TransactionDetailsAssembler assembler) {
        this.transactions = transactions;
        this.assembler = assembler;
    }

    @Override
    public TransactionDetails handle(GetTransactionQuery query) {
        return transactions.findById(query.userId(), query.transactionId()).map(assembler::assemble)
                .orElseThrow(() -> new TransactionNotFoundException(query.transactionId()));
    }
}
