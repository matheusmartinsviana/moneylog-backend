package com.moneylog.infra.note.controller;

import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;

import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.servlet.http.HttpServletResponse;

import com.moneylog.core.note.app.usecase.ExportVaultUseCase;
import com.moneylog.core.note.app.usecase.ExportVaultUseCase.ExportVaultQuery;
import com.moneylog.core.note.app.usecase.ExportVaultUseCase.VaultExport;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase.ConflictMode;
import com.moneylog.core.note.app.usecase.ImportVaultUseCase.ImportVaultCommand;
import com.moneylog.core.note.app.vault.ZipSource;
import com.moneylog.core.note.exception.NoteExceptions.VaultImportInvalidException;
import com.moneylog.infra.export.controller.ExportRateLimiter;
import com.moneylog.infra.note.controller.NoteResponses.ImportReportResponse;
import com.moneylog.infra.security.CurrentUser;

@RestController
@RequestMapping("/v1/vault")
public class VaultController {

    private final ExportVaultUseCase export;
    private final ImportVaultUseCase importVault;
    private final ExportRateLimiter rateLimiter;

    public VaultController(ExportVaultUseCase export, ImportVaultUseCase importVault, ExportRateLimiter rateLimiter) {
        this.export = export;
        this.importVault = importVault;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping(value = "/export", produces = "application/zip")
    public void export(@CurrentUser UUID userId, HttpServletResponse response) throws IOException {
        rateLimiter.acquire(userId);
        try (VaultExport vault = export.handle(new ExportVaultQuery(userId))) {
            response.setContentType("application/zip");
            response.setHeader(HttpHeaders.CONTENT_DISPOSITION, "attachment; filename=\"" + vault.fileName() + "\"");
            response.setHeader(HttpHeaders.CACHE_CONTROL, "no-store");
            response.setContentLengthLong(vault.sizeBytes());
            vault.writeTo(response.getOutputStream());
        }
    }

    @PostMapping(value = "/import", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ImportReportResponse importZip(@CurrentUser UUID userId, @RequestPart("file") MultipartFile file,
                                          @RequestParam(defaultValue = "SKIP") ConflictMode mode) {
        rateLimiter.acquire(userId);
        if (file.isEmpty()) {
            throw new VaultImportInvalidException("o arquivo está vazio.");
        }
        return ImportReportResponse.of(importVault.handle(new ImportVaultCommand(userId, new MultipartZipSource(file), mode)));
    }

    private record MultipartZipSource(MultipartFile file) implements ZipSource {
        @Override
        public InputStream open() throws IOException {
            return file.getInputStream();
        }

        @Override
        public long size() {
            return file.getSize();
        }
    }
}
