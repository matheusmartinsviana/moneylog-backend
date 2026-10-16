package com.moneylog.infra.thirdparty;

import java.util.List;
import java.util.UUID;

import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.moneylog.core.sk.money.Money;
import com.moneylog.core.finance.app.migration.ApplyShareMigrationUseCase;
import com.moneylog.core.finance.app.migration.ApplyShareMigrationUseCase.ApplyShareMigrationCommand;
import com.moneylog.core.finance.app.migration.PreviewShareMigrationUseCase;
import com.moneylog.core.finance.app.migration.PreviewShareMigrationUseCase.PreviewShareMigrationQuery;
import com.moneylog.core.thirdparty.domain.ShareMigrationReport;
import com.moneylog.infra.security.CurrentUser;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Pattern;

@RestController
@RequestMapping("/v1/third-party-expenses/share-migration")
public class ShareMigrationController {

    private final PreviewShareMigrationUseCase preview;
    private final ApplyShareMigrationUseCase apply;

    public ShareMigrationController(PreviewShareMigrationUseCase preview, ApplyShareMigrationUseCase apply) {
        this.preview = preview;
        this.apply = apply;
    }

    public record ApplyInput(@NotNull @Pattern(regexp = "^[0-9a-f]{64}$") String reportHash, @NotNull Boolean backupConfirmed) {
    }

    public record MonthResponse(String competence, Money consumptionBefore, Money consumptionMineAfter, Money receivablePending,
                                Money reimbursed) {

        static MonthResponse of(ShareMigrationReport.MonthEntry m) {
            return new MonthResponse(m.competence().toString(), m.consumptionBefore(), m.consumptionMineAfter(), m.receivablePending(),
                    m.reimbursed());
        }
    }

    public record PurchaseResponse(UUID transactionId, UUID personId, ShareMigrationReport.Status status,
                                   ShareMigrationReport.ExceptionReason reason, String splitMode, Money charged, Money third,
                                   Money refunded, Money legacyReceived, List<MonthResponse> months) {
    }

    public record ReportResponse(String hash, int eligible, int exceptions, Money receivableRecognized, Money refundsNeutralized,
                                 List<PurchaseResponse> purchases, List<MonthResponse> months) {
    }

    public record ApplyResponse(String reportHash, boolean alreadyApplied, int migratedPurchases, int refundsNeutralized,
                                int legacyReceiptsLedgered) {
    }

    @PostMapping("/dry-run")
    public ReportResponse dryRun(@CurrentUser UUID userId) {
        ShareMigrationReport report = preview.handle(new PreviewShareMigrationQuery(userId));
        return new ReportResponse(report.hash(), report.eligible(), report.exceptions(), report.receivableRecognized(),
                report.refundsNeutralized(), report.purchases().stream().map(p -> new PurchaseResponse(p.transactionId(), p.personId(),
                        p.status(), p.reason(), p.mode() == null ? null : p.mode().name(), p.charged(), p.third(), p.refunded(),
                        p.legacyReceived(), p.months().stream().map(MonthResponse::of).toList())).toList(),
                report.months().stream().map(MonthResponse::of).toList());
    }

    @PostMapping("/apply")
    public ApplyResponse apply(@CurrentUser UUID userId, @Valid @RequestBody ApplyInput body) {
        var result = apply.handle(new ApplyShareMigrationCommand(userId, body.reportHash(), Boolean.TRUE.equals(body.backupConfirmed())));
        return new ApplyResponse(result.reportHash(), result.alreadyApplied(), result.migratedPurchases(), result.refundsNeutralized(),
                result.legacyReceiptsLedgered());
    }
}
