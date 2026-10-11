package com.moneylog.core.finance.app.transactions;

import com.moneylog.core.finance.domain.CashFlowReport;
import com.moneylog.core.sk.page.Slice;

public record TransactionListing(Slice<TransactionDetails> items, int count, CashFlowReport cashFlow) {
}
