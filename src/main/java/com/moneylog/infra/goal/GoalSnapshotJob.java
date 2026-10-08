package com.moneylog.infra.goal;

import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Scheduled;

import com.moneylog.core.finance.app.goals.usecase.RecordGoalSnapshotsUseCase;
import com.moneylog.core.goal.app.gateways.GoalSnapshotGateway;

/**
 * Job diário dos instantâneos da medida das metas (ADR-016): às 23:55 em {@code America/Sao_Paulo}, para o último valor do dia entrar no
 * mês. <b>Idempotente</b> (um registro por meta e mês, atualizado até o mês fechar): reexecutar ou reiniciar não duplica. A falha de um
 * usuário é registrada (sem dados) e os demais seguem. Uma instância só (ADR-001).
 */
public class GoalSnapshotJob {

    private static final Logger log = LoggerFactory.getLogger(GoalSnapshotJob.class);

    private final GoalSnapshotGateway snapshots;
    private final RecordGoalSnapshotsUseCase record;

    public GoalSnapshotJob(GoalSnapshotGateway snapshots, RecordGoalSnapshotsUseCase record) {
        this.snapshots = snapshots;
        this.record = record;
    }

    @Scheduled(cron = "${moneylog.goals.snapshot.cron:0 55 23 * * *}", zone = "${moneylog.zone:America/Sao_Paulo}")
    public void run() {
        runOnce();
    }

    /** Uma passada completa; devolve quantos instantâneos foram gravados. Público para o teste chamar sem esperar o agendador. */
    public int runOnce() {
        int recorded = 0;
        List<UUID> users = snapshots.userIdsWithTrackedGoals();
        for (UUID userId : users) {
            try {
                recorded += record.handle(userId);
            } catch (RuntimeException e) {
                log.error("Falha ao gravar instantâneos de metas de um usuário (userId={}, erro={})", userId, e.getClass().getSimpleName());
            }
        }
        return recorded;
    }
}
