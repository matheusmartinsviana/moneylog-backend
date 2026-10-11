package com.moneylog.core.importing.app;

import com.moneylog.core.importing.app.gateways.ImportBatchGateway.Counts;
import com.moneylog.core.importing.domain.ImportBatch;

public record ImportBatchView(ImportBatch batch, Counts counts) {
}
