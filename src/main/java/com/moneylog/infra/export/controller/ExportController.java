package com.moneylog.infra.export.controller;

import java.io.IOException;
import java.time.LocalDate;
import java.util.Locale;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletResponse;
import jakarta.validation.constraints.Size;

import com.moneylog.core.finance.app.export.BackupSnapshot;
import com.moneylog.core.finance.app.export.CsvExport;
import com.moneylog.core.finance.app.export.ExportBackupUseCase;
import com.moneylog.core.finance.app.export.ExportBackupUseCase.ExportBackupQuery;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCase;
import com.moneylog.core.finance.app.export.ExportTransactionsCsvUseCase.ExportTransactionsCsvQuery;
import com.moneylog.core.sk.time.TimeProvider;
import com.moneylog.core.transaction.app.query.TransactionFilter;
import com.moneylog.core.transaction.domain.PaymentMethod;
import com.moneylog.core.transaction.domain.TransactionStatus;
import com.moneylog.core.transaction.domain.TransactionType;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryDates;
import com.moneylog.infra.web.validation.NoControlChars;

@RestController
@RequestMapping("/v1/exports")
@Validated
public class ExportController {

    private final ExportTransactionsCsvUseCase csv;
    private final ExportBackupUseCase backup;

    private final ExportRateLimiter rateLimiter;
    private final TimeProvider time;

    public ExportController(ExportTransactionsCsvUseCase csv, ExportBackupUseCase backup, ExportRateLimiter rateLimiter, TimeProvider time) {
        this.csv = csv;
        this.backup = backup;
        this.rateLimiter = rateLimiter;
        this.time = time;
    }

    @GetMapping(value = "/transactions.csv", produces = "text/csv")
    public void transactions(@CurrentUser UUID userId,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
                             @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
                             @RequestParam(required = false) TransactionType type, @RequestParam(required = false) UUID accountId,
                             @RequestParam(required = false) UUID categoryId, @RequestParam(required = false) PaymentMethod method,
                             @RequestParam(required = false) TransactionStatus status,
                             @RequestParam(required = false) @Size(max = TransactionFilter.Q_MAX_LENGTH) @NoControlChars String q,
                             @RequestParam(defaultValue = "false") boolean includeVoided,
                             @RequestHeader(value = HttpHeaders.ACCEPT_LANGUAGE, required = false) String acceptLanguage,
                             HttpServletResponse response) throws IOException {
        rateLimiter.acquire(userId);
        QueryDates.inWindow(from, "from");
        QueryDates.inWindow(to, "to");
        if (from != null && to != null && from.isAfter(to)) {
            throw new RequestValidationException("from", "INVALID_RANGE", "'from' não pode ser depois de 'to'.");
        }
        TransactionFilter filter = new TransactionFilter(from, to, type, accountId, categoryId, method, status, q);
        CsvExport export = csv.handle(new ExportTransactionsCsvQuery(userId, filter, includeVoided && status == null, language(acceptLanguage)));
        response.setContentType("text/csv;charset=UTF-8");
        response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + export.fileName() + "\"");
        response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
        export.writeTo(response.getOutputStream());
    }

    @GetMapping(value = "/backup", produces = "application/json")
    public ResponseEntity<BackupResponse> backup(@CurrentUser UUID userId) {
        rateLimiter.acquire(userId);
        BackupSnapshot snapshot = backup.handle(new ExportBackupQuery(userId));
        String day = snapshot.generatedAt().atZone(time.zone()).toLocalDate().toString();
        return ResponseEntity.ok()
                .header(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"moneylog-backup-" + day + ".json\"")
                .header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(BackupResponse.of(snapshot));
    }

    static Locale language(String acceptLanguage) {
        if (acceptLanguage == null || acceptLanguage.isBlank()) {
            return Locale.forLanguageTag("pt-BR");
        }
        try {
            return Locale.LanguageRange.parse(acceptLanguage).stream().findFirst().map(r -> Locale.forLanguageTag(r.getRange()))
                    .orElse(Locale.forLanguageTag("pt-BR"));
        } catch (IllegalArgumentException e) {
            return Locale.forLanguageTag("pt-BR");
        }
    }
}
