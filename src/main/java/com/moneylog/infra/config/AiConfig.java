package com.moneylog.infra.config;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.env.Environment;
import org.springframework.core.env.Profiles;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.ai.app.AIService;
import com.moneylog.core.ai.app.AiGuard;
import com.moneylog.core.ai.app.LlmClient;
import com.moneylog.core.ai.app.NoopAIService;
import com.moneylog.core.ai.app.gateways.AiAnalysisGateway;
import com.moneylog.core.ai.app.gateways.AiPreviewGateway;
import com.moneylog.core.ai.app.gateways.AiSettingsGateway;
import com.moneylog.core.ai.app.usecase.AiUseCases.CreateAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiSettingsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiStatusUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetInsightsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.ListAiAnalysesUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.PurgeExpiredAiDataUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunApprovedAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.SaveAiSettingsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCasesImpl;
import com.moneylog.core.category.app.gateways.CategoryGateway;
import com.moneylog.core.finance.app.gateways.FinancialRecordsGateway;
import com.moneylog.core.sk.id.IdGenerator;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.infra.ai.AiServiceImpl;
import com.moneylog.infra.ai.config.AiProperties;
import com.moneylog.infra.ai.gateways.AiAnalysisGatewayImpl;
import com.moneylog.infra.ai.gateways.AiPreviewGatewayImpl;
import com.moneylog.infra.ai.gateways.AiSettingsGatewayImpl;
import com.moneylog.infra.ai.jobs.PayloadRetentionJob;
import com.moneylog.infra.ai.llm.AnthropicLlmClient;
import com.moneylog.infra.ai.llm.FakeLlmClient;

/**
 * Ligação da IA (Etapa 8). {@code AI_PROVIDER=none} (padrão) liga o {@link NoopAIService}: o app inteiro funciona sem chave. {@code anthropic}
 * sem {@code AI_API_KEY}/{@code AI_MODEL} derruba a subida com mensagem que não imprime a chave; {@code fake} em produção também.
 * Os gateways são transacionais por chamada: o caso de uso de envio não segura transação enquanto espera o provedor.
 */
@Configuration(proxyBeanMethods = false)
public class AiConfig {

    private static final Logger log = LoggerFactory.getLogger(AiConfig.class);
    private static final Duration RETRY_BACKOFF = Duration.ofMillis(500);

    @Bean
    AiProperties aiProperties(@Value("${moneylog.ai.provider:none}") String provider, @Value("${moneylog.ai.api-key:}") String apiKey,
                              @Value("${moneylog.ai.model:}") String model, @Value("${moneylog.ai.base-url:}") String baseUrl,
                              @Value("${moneylog.ai.timeout-seconds:30}") int timeoutSeconds,
                              @Value("${moneylog.ai.daily-call-limit:50}") int dailyCallLimit,
                              @Value("${moneylog.ai.monthly-token-budget:500000}") long monthlyTokenBudget,
                              @Value("${moneylog.ai.payload-retention-days:90}") int retentionDays, Environment environment) {
        AiProperties properties = new AiProperties(provider, apiKey, model, baseUrl, timeoutSeconds, dailyCallLimit, monthlyTokenBudget, retentionDays);
        if (AiProperties.FAKE.equals(properties.provider()) && environment.acceptsProfiles(Profiles.of("prod"))) {
            throw new IllegalStateException("AI_PROVIDER=fake não é permitido em produção.");
        }
        return properties;
    }

    @Bean
    AIService aiService(AiProperties properties, ObjectMapper mapper) {
        if (!properties.enabled()) {
            log.info("IA desligada (AI_PROVIDER=none): o app funciona normalmente sem ela.");
            return new NoopAIService();
        }
        LlmClient client = AiProperties.FAKE.equals(properties.provider()) ? new FakeLlmClient()
                : new AnthropicLlmClient(mapper, properties.baseUrl(), properties.apiKey(), properties.model(), Duration.ofSeconds(properties.timeoutSeconds()));
        log.info("IA ligada: provedor={}, modelo={}", properties.provider(), properties.model());
        return new AiServiceImpl(client, mapper, properties.provider(), properties.model(), RETRY_BACKOFF);
    }

    // ---- gateways (uma transação curta por chamada)

    @Bean
    AiAnalysisGateway aiAnalysisGateway(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager tm) {
        return TransactionalProxies.required(AiAnalysisGateway.class, new AiAnalysisGatewayImpl(jdbc, mapper), tm);
    }

    @Bean
    AiPreviewGateway aiPreviewGateway(JdbcTemplate jdbc, ObjectMapper mapper, PlatformTransactionManager tm) {
        return TransactionalProxies.required(AiPreviewGateway.class, new AiPreviewGatewayImpl(jdbc, mapper), tm);
    }

    @Bean
    AiSettingsGateway aiSettingsGateway(JdbcTemplate jdbc, PlatformTransactionManager tm) {
        return TransactionalProxies.required(AiSettingsGateway.class, new AiSettingsGatewayImpl(jdbc), tm);
    }

    @Bean
    AiGuard aiGuard(AiAnalysisGateway analyses, TimeProvider time, AiProperties properties) {
        return new AiGuard(analyses, time, new AiGuard.Limits(properties.dailyCallLimit(), properties.monthlyTokenBudget()));
    }

    // ---- casos de uso

    @Bean
    GetInsightsUseCase getInsightsUseCase(FinancialRecordsGateway records, CategoryGateway categories, TimeProvider time, PlatformTransactionManager tm) {
        return TransactionalProxies.snapshotReadOnly(GetInsightsUseCase.class, new AiUseCasesImpl.GetInsights(records, categories, time), tm);
    }

    @Bean
    GetAiStatusUseCase getAiStatusUseCase(AIService ai, AiGuard guard) {
        return new AiUseCasesImpl.GetAiStatus(ai, guard);
    }

    @Bean
    CreateAiPreviewUseCase createAiPreviewUseCase(GetInsightsUseCase insights, AiPreviewGateway previews, IdGenerator ids, TimeProvider time) {
        return new AiUseCasesImpl.CreateAiPreview(insights, previews, ids, time);
    }

    @Bean
    GetAiPreviewUseCase getAiPreviewUseCase(AiPreviewGateway previews) {
        return new AiUseCasesImpl.GetAiPreview(previews);
    }

    /** Sem transação própria de propósito: o provedor é chamado fora de qualquer transação (efeito colateral só depois de gravar a reivindicação). */
    @Bean
    RunApprovedAnalysisUseCase runApprovedAnalysisUseCase(AIService ai, AiGuard guard, AiPreviewGateway previews, AiAnalysisGateway analyses,
                                                          IdGenerator ids, TimeProvider time) {
        return new AiUseCasesImpl.RunApprovedAnalysis(ai, guard, previews, analyses, ids, time);
    }

    @Bean
    ListAiAnalysesUseCase listAiAnalysesUseCase(AiAnalysisGateway analyses) {
        return new AiUseCasesImpl.ListAiAnalyses(analyses);
    }

    @Bean
    GetAiAnalysisUseCase getAiAnalysisUseCase(AiAnalysisGateway analyses) {
        return new AiUseCasesImpl.GetAiAnalysis(analyses);
    }

    @Bean
    GetAiSettingsUseCase getAiSettingsUseCase(AiSettingsGateway settings) {
        return new AiUseCasesImpl.GetAiSettings(settings);
    }

    @Bean
    SaveAiSettingsUseCase saveAiSettingsUseCase(AiSettingsGateway settings, AIService ai) {
        return new AiUseCasesImpl.SaveAiSettings(settings, ai);
    }

    @Bean
    PurgeExpiredAiDataUseCase purgeExpiredAiDataUseCase(AiAnalysisGateway analyses, AiPreviewGateway previews, TimeProvider time,
                                                        AiProperties properties) {
        return new AiUseCasesImpl.PurgeExpiredAiData(analyses, previews, time, properties.payloadRetentionDays());
    }

    @Bean
    PayloadRetentionJob payloadRetentionJob(PurgeExpiredAiDataUseCase purge) {
        return new PayloadRetentionJob(purge);
    }
}
