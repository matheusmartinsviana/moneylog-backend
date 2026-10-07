package com.moneylog.core.importing.app;

import com.moneylog.core.importing.app.gateways.ImportBatchGateway.Counts;
import com.moneylog.core.importing.domain.ImportBatch;

/** O lote com os contadores calculados pelo backend (o front só exibe). */
public record ImportBatchView(ImportBatch batch, Counts counts) {
}
