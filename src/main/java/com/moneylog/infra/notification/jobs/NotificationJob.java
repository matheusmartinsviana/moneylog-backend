package com.moneylog.infra.notification.jobs;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.moneylog.core.notification.app.gateways.NotificationGateway;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.ArchiveOldNotificationsUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.DispatchPushUseCase;
import com.moneylog.core.notification.app.usecase.NotificationUseCases.GenerateNotificationsUseCase;

public class NotificationJob {

    private static final Logger log = LoggerFactory.getLogger(NotificationJob.class);

    private final NotificationGateway notifications;
    private final GenerateNotificationsUseCase generate;
    private final DispatchPushUseCase dispatch;
    private final ArchiveOldNotificationsUseCase archive;

    public NotificationJob(NotificationGateway notifications, GenerateNotificationsUseCase generate, DispatchPushUseCase dispatch,
                           ArchiveOldNotificationsUseCase archive) {
        this.notifications = notifications;
        this.generate = generate;
        this.dispatch = dispatch;
        this.archive = archive;
    }

    @Scheduled(cron = "${moneylog.notifications.job.cron:0 */15 * * * *}", zone = "${moneylog.zone:America/Sao_Paulo}")
    public void run() {
        runOnce();
    }

    public int runOnce() {
        int created = 0;
        List<UUID> users = notifications.allUserIds();
        for (UUID userId : users) {
            try {
                created += generate.handle(userId);
            } catch (RuntimeException e) {
                log.error("Falha ao gerar notificações de um usuário (userId={}, erro={})", userId, e.getClass().getSimpleName());
            }
        }
        try {
            dispatch.handle();
            archive.handle();
        } catch (RuntimeException e) {
            log.error("Falha no despacho de push ou na retenção (erro={})", e.getClass().getSimpleName());
        }
        return created;
    }
}
