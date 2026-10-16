package com.moneylog.core.finance.domain;

public enum ConsumptionView {
    MINE,
    GROSS;

    public static ConsumptionView orDefault(ConsumptionView view) {
        return view == null ? MINE : view;
    }

    public Ledger ledgerOf(FinancialRecords records) {
        return this == GROSS ? CardShareReceivables.grossLedger(records) : records.ledger();
    }
}
