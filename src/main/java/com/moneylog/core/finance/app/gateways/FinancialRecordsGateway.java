package com.moneylog.core.finance.app.gateways;

import java.util.UUID;

import com.moneylog.core.finance.domain.FinancialRecords;

public interface FinancialRecordsGateway {

    FinancialRecords load(UUID userId);
}
