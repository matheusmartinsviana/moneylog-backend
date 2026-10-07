package com.moneylog.core.ai.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.moneylog.core.ai.app.AiResult.AiUnavailable;
import com.moneylog.core.ai.app.AiResult.Reason;
import com.moneylog.core.ai.domain.AiFacts;
import com.moneylog.core.ai.domain.AiPurpose;

class NoopAIServiceTest {

    @Test
    void deveDevolverIndisponivelEmTodasAsOperacoesSemLancar() {
        NoopAIService noop = new NoopAIService();
        AiRequest request = new AiRequest(UUID.randomUUID(), AiPurpose.MONTHLY_SUMMARY, "pt", "{}", new AiFacts(Map.of()));

        assertThat(noop.enabled()).isFalse();
        assertThat(noop.provider()).isEqualTo("none");
        for (AiResult result : new AiResult[] {noop.analyzeStatement(request), noop.categorizeTransaction(request),
                noop.generateFinancialSummary(request), noop.generateInsights(request), noop.generateForecast(request),
                noop.generateDailyMessage(request)}) {
            assertThat(result).isInstanceOf(AiUnavailable.class);
            assertThat(((AiUnavailable) result).reason()).isEqualTo(Reason.DISABLED);
        }
    }
}
