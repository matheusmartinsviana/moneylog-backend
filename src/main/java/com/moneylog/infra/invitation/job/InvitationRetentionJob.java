package com.moneylog.infra.invitation.job;

import java.time.Duration;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase.PurgeInvitationsCommand;
import com.moneylog.core.invitation.app.usecase.PurgeInvitationsUseCase.PurgeResult;

public class InvitationRetentionJob {

    private static final Logger log = LoggerFactory.getLogger(InvitationRetentionJob.class);

    private final PurgeInvitationsUseCase purge;
    private final Duration retention;

    public InvitationRetentionJob(PurgeInvitationsUseCase purge, Duration retention) {
        this.purge = purge;
        this.retention = retention;
    }

    @Scheduled(cron = "0 45 3 * * *")
    public void run() {
        try {
            PurgeResult result = purge.handle(new PurgeInvitationsCommand(retention));
            if (result.expired() > 0 || result.deleted() > 0) {
                log.info("Convites: {} vencidos gravados como EXPIRED, {} finais apagados.", result.expired(), result.deleted());
            }
        } catch (RuntimeException e) {
            log.warn("Falha na retenção de convites; tenta de novo na próxima execução.", e);
        }
    }
}
