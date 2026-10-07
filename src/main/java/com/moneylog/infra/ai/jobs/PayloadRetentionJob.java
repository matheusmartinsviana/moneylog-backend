package com.moneylog.infra.ai.jobs;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.moneylog.core.ai.app.usecase.AiUseCases.PurgeExpiredAiDataUseCase;

/** Retenção (S-183): uma vez por dia apaga o payload enviado com mais de N dias (padrão 90) e as prévias vencidas; o registro permanece. */
public class PayloadRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(PayloadRetentionJob.class);

    private final PurgeExpiredAiDataUseCase purge;

    public PayloadRetentionJob(PurgeExpiredAiDataUseCase purge) {
        this.purge = purge;
    }

    @Scheduled(cron = "0 30 3 * * *", zone = "${moneylog.zone:America/Sao_Paulo}")
    public void run() {
        try {
            int purged = purge.handle();
            log.info("Retenção de payloads de IA: {} apagados", purged);
        } catch (RuntimeException e) {
            log.error("Falha na retenção de payloads de IA (erro={})", e.getClass().getSimpleName());
        }
    }
}
