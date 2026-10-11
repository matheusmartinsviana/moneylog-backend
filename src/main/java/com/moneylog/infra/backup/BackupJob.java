package com.moneylog.infra.backup;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.moneylog.core.backup.app.BackupService;

public class BackupJob {

    private static final Logger log = LoggerFactory.getLogger(BackupJob.class);

    private final BackupService service;

    public BackupJob(BackupService service) {
        this.service = service;
    }

    @Scheduled(cron = "${moneylog.backup.cron:0 0 3 * * *}", zone = "UTC")
    public void run() {
        try {
            String key = service.backup();
            log.info("Backup concluído: {}", key);
        } catch (RuntimeException e) {
            log.error("Backup falhou: {}", e.getMessage());
        }
    }
}
