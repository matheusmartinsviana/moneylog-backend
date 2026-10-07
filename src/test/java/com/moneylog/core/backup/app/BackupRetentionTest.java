package com.moneylog.core.backup.app;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

@DisplayName("Retenção de backups")
class BackupRetentionTest {

    private static StoredBackup at(String day, String hour) {
        return new StoredBackup("moneylog/" + day + "-" + hour, Instant.parse(day + "T" + hour + ":00:00Z"), 10);
    }

    @Test
    @DisplayName("mantém os 7 dias mais recentes e apaga o resto")
    void deveManterSeteDias() {
        List<StoredBackup> all = new ArrayList<>();
        for (int d = 1; d <= 10; d++) {
            all.add(at("2026-10-%02d".formatted(d), "03"));
        }
        List<StoredBackup> del = BackupRetention.toDelete(all, 7);
        assertThat(del).extracting(StoredBackup::key)
                .containsExactlyInAnyOrder("moneylog/2026-10-01-03", "moneylog/2026-10-02-03", "moneylog/2026-10-03-03");
    }

    @Test
    @DisplayName("vários backups no mesmo dia contam como um dia; dia sem backup não gasta retenção")
    void deveContarDiasComBackup() {
        List<StoredBackup> all = List.of(at("2026-10-10", "03"), at("2026-10-10", "15"), at("2026-10-01", "03"));
        assertThat(BackupRetention.toDelete(all, 2)).isEmpty();
        assertThat(BackupRetention.toDelete(all, 1)).extracting(StoredBackup::key).containsExactly("moneylog/2026-10-01-03");
    }
}
