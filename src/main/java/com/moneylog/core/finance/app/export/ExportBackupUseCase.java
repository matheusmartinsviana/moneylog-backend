package com.moneylog.core.finance.app.export;

import java.util.UUID;

public interface ExportBackupUseCase {

    /**
     * Teto de movimentações do backup e do CSV (S-260): 200.000, bem acima do medido (50.000 movimentações = backup de 57 MB e heap de
     * +121 MB). Acima dele, {@code 422 EXPORT_TOO_LARGE}. Configurável por {@code moneylog.export.max-transactions}.
     */
    int MAX_TRANSACTIONS = 200_000;

    /**
     * Backup completo do usuário (S-153): contas, categorias, cartões, movimentações (com parcelas), dívidas, pessoas, recorrências,
     * metas com reservas e os lançamentos do razão, em um instantâneo único. Só os dados do próprio usuário; nunca hash de senha,
     * tokens nem segredos. Mais que {@link #MAX_TRANSACTIONS} movimentações: {@link ExportTooLargeException}.
     */
    BackupSnapshot handle(ExportBackupQuery query);

    record ExportBackupQuery(UUID userId) {
    }
}
