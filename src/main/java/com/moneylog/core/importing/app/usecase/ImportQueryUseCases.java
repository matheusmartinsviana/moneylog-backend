package com.moneylog.core.importing.app.usecase;

import java.util.UUID;

import com.moneylog.core.importing.app.ImportBatchView;
import com.moneylog.core.importing.app.gateways.ImportHistoryGateway.StoredRule;
import com.moneylog.core.importing.domain.ImportBatchLine;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.page.Slice;

/** Leituras da importação (cada uma filtra pelo usuário do token). */
public interface ImportQueryUseCases {

    interface GetImportBatchUseCase {
        ImportBatchView handle(UUID userId, UUID batchId);
    }

    interface ListImportBatchesUseCase {
        Slice<ImportBatchView> handle(UUID userId, ImportBatchStatus status, PageQuery page);
    }

    interface ListImportLinesUseCase {
        Slice<ImportBatchLine> handle(UUID userId, UUID batchId, ImportLineStatus status, ImportLineFlag flag, PageQuery page);
    }

    /** Descarta um lote ainda em revisão (nada foi aplicado, então nada é estornado). Lote aplicado/desfeito: 422. */
    interface DiscardImportBatchUseCase {
        void handle(UUID userId, UUID batchId);
    }

    interface ListCategorizationRulesUseCase {
        Slice<StoredRule> handle(UUID userId, PageQuery page);
    }

    interface DeleteCategorizationRuleUseCase {
        void handle(UUID userId, UUID ruleId);
    }

    interface GetImportMappingUseCase {
        /** Falha com {@code IMPORT_MAPPING_NOT_FOUND} (404) se ainda não há mapeamento salvo para a conta/cartão. */
        ColumnMapping handle(UUID userId, UUID sourceId);
    }

    interface SaveImportMappingUseCase {
        ColumnMapping handle(UUID userId, UUID sourceId, ColumnMapping mapping);
    }
}
