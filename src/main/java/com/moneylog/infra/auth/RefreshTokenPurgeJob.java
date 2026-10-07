package com.moneylog.infra.auth;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.scheduling.annotation.Scheduled;

import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCase;
import com.moneylog.core.auth.app.usecase.PurgeExpiredRefreshTokensUseCase.PurgeCommand;
import com.moneylog.infra.security.MoneylogAuthProperties;

/** Limpa os refresh tokens expirados (B7): uma vez no boot e todo dia às 03:30. Falha só vira log (tentará de novo). */
public class RefreshTokenPurgeJob implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(RefreshTokenPurgeJob.class);

    private final PurgeExpiredRefreshTokensUseCase purge;
    private final MoneylogAuthProperties auth;

    public RefreshTokenPurgeJob(PurgeExpiredRefreshTokensUseCase purge, MoneylogAuthProperties auth) {
        this.purge = purge;
        this.auth = auth;
    }

    @Override
    public void run(ApplicationArguments args) {
        purgeNow();
    }

    @Scheduled(cron = "0 30 3 * * *")
    public void purgeNow() {
        try {
            int deleted = purge.handle(new PurgeCommand(auth.refreshRetention()));
            if (deleted > 0) {
                log.info("Refresh tokens expirados apagados: {}.", deleted);
            }
        } catch (RuntimeException e) {
            log.warn("Falha ao limpar refresh tokens expirados; tenta de novo na próxima execução.", e);
        }
    }
}
