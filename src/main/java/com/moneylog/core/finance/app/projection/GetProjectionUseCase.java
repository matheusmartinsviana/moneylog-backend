package com.moneylog.core.finance.app.projection;

import java.util.UUID;

import com.moneylog.core.finance.domain.Horizon;
import com.moneylog.core.finance.domain.Projection;

public interface GetProjectionUseCase {

    /**
     * Projeção determinística (S-152, S-155): série mensal dos compromissos até o horizonte ({@code D30}, {@code D90}, {@code M6},
     * {@code M12}), sempre {@code isProjection = true}, com as premissas. A tendência histórica ({@code includeTrend}) é opcional,
     * desligada por padrão, separada e rotulada estimativa.
     */
    Projection handle(GetProjectionQuery query);

    record GetProjectionQuery(UUID userId, Horizon horizon, boolean includeTrend) {
    }
}
