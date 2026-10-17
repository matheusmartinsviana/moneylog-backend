package com.moneylog.infra.export.controller;

import java.util.Map;
import java.util.UUID;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.finance.app.export.BackupExceptions.RestoreConflictException;
import com.moneylog.core.finance.app.export.BackupSnapshot;
import com.moneylog.core.finance.app.export.RestoreBackupUseCase;
import com.moneylog.core.finance.app.export.RestoreBackupUseCase.RestoreBackupCommand;
import com.moneylog.core.finance.app.export.RestoreBackupUseCase.RestoreSummary;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/backups")
public class BackupRestoreController {

    private final RestoreBackupUseCase restore;
    private final ExportRateLimiter rateLimiter;

    public BackupRestoreController(RestoreBackupUseCase restore, ExportRateLimiter rateLimiter) {
        this.restore = restore;
        this.rateLimiter = rateLimiter;
    }

    public record RestoreResponse(int schemaVersion, Map<String, Integer> counts) {

        static RestoreResponse of(RestoreSummary summary) {
            return new RestoreResponse(summary.schemaVersion(), summary.counts());
        }
    }

    @PostMapping(value = "/restore", consumes = "application/json", produces = "application/json")
    public ResponseEntity<RestoreResponse> restore(@CurrentUser UUID userId, @RequestBody BackupResponse body) {
        rateLimiter.acquire(userId);
        BackupSnapshot snapshot = BackupRestoreMapper.toSnapshot(userId, body);
        try {
            RestoreSummary summary = restore.handle(new RestoreBackupCommand(userId, snapshot));
            return ResponseEntity.ok().header(HttpHeaders.CACHE_CONTROL, "no-store").body(RestoreResponse.of(summary));
        } catch (DataIntegrityViolationException e) {
            throw new RestoreConflictException();
        }
    }
}
