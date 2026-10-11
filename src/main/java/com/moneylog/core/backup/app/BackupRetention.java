package com.moneylog.core.backup.app;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Set;
import java.util.TreeSet;

public final class BackupRetention {

    private BackupRetention() {
    }

    public static List<StoredBackup> toDelete(List<StoredBackup> backups, int keepDays) {
        if (keepDays < 1) {
            throw new IllegalArgumentException("keepDays deve ser >= 1");
        }
        Set<LocalDate> days = new TreeSet<>(java.util.Comparator.reverseOrder());
        for (StoredBackup b : backups) {
            days.add(dayOf(b.lastModified()));
        }
        Set<LocalDate> kept = new TreeSet<>(days.stream().limit(keepDays).toList());
        return backups.stream().filter(b -> !kept.contains(dayOf(b.lastModified()))).toList();
    }

    private static LocalDate dayOf(Instant instant) {
        return instant.atZone(ZoneOffset.UTC).toLocalDate();
    }
}
