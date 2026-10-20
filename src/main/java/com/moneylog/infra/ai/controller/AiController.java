package com.moneylog.infra.ai.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.Authentication;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.ai.app.usecase.AiUseCases.CreateAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.CreatePreviewCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiPreviewUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiSettingsUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.GetAiStatusUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.ListAiAnalysesUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunAnalysisCommand;
import com.moneylog.core.ai.app.usecase.AiUseCases.RunApprovedAnalysisUseCase;
import com.moneylog.core.ai.app.usecase.AiUseCases.SaveAiSettingsUseCase;
import com.moneylog.core.ai.domain.AiPurpose;
import com.moneylog.core.ai.domain.AiStatus;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.time.Competence;
import com.moneylog.infra.ai.controller.AiDtos.AnalysisInput;
import com.moneylog.infra.ai.controller.AiDtos.AnalysisResponse;
import com.moneylog.infra.ai.controller.AiDtos.AnalysisSummaryResponse;
import com.moneylog.infra.ai.controller.AiDtos.PreviewInput;
import com.moneylog.infra.ai.controller.AiDtos.PreviewResponse;
import com.moneylog.infra.ai.controller.AiDtos.SettingsDto;
import com.moneylog.infra.ai.controller.AiDtos.StatusResponse;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;
import com.moneylog.infra.web.SliceResponse;

@RestController
public class AiController {

    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final GetAiStatusUseCase status;
    private final CreateAiPreviewUseCase createPreview;
    private final GetAiPreviewUseCase getPreview;
    private final RunApprovedAnalysisUseCase runAnalysis;
    private final ListAiAnalysesUseCase listAnalyses;
    private final GetAiAnalysisUseCase getAnalysis;
    private final GetAiSettingsUseCase getSettings;
    private final SaveAiSettingsUseCase saveSettings;

    @SuppressWarnings("java:S107")
    public AiController(GetAiStatusUseCase status, CreateAiPreviewUseCase createPreview, GetAiPreviewUseCase getPreview,
                        RunApprovedAnalysisUseCase runAnalysis, ListAiAnalysesUseCase listAnalyses, GetAiAnalysisUseCase getAnalysis,
                        GetAiSettingsUseCase getSettings, SaveAiSettingsUseCase saveSettings) {
        this.status = status;
        this.createPreview = createPreview;
        this.getPreview = getPreview;
        this.runAnalysis = runAnalysis;
        this.listAnalyses = listAnalyses;
        this.getAnalysis = getAnalysis;
        this.getSettings = getSettings;
        this.saveSettings = saveSettings;
    }

    @GetMapping("/v1/ai/status")
    public ResponseEntity<StatusResponse> status(@CurrentUser UUID userId, Authentication authentication) {
        boolean owner = authentication != null && authentication.getAuthorities().stream()
                .anyMatch(a -> "ROLE_OWNER".equals(a.getAuthority()));
        StatusResponse body = owner ? StatusResponse.of(status.handle(userId)) : StatusResponse.unavailable();
        return ResponseEntity.ok().cacheControl(NO_STORE).body(body);
    }

    @PostMapping("/v1/ai/previews")
    public ResponseEntity<PreviewResponse> createPreview(@CurrentUser UUID userId, @Valid @RequestBody PreviewInput body) {
        Competence month = body.scope() == null || body.scope().month() == null ? null
                : QueryMonths.parse(body.scope().month(), "AI_SCOPE_MONTH_INVALID", "scope.month");
        String language = body.scope() == null ? null : body.scope().language();
        PreviewResponse created = PreviewResponse.of(createPreview.handle(new CreatePreviewCommand(userId, body.purpose(), month, language)));
        return ResponseEntity.created(URI.create("/v1/ai/previews/" + created.previewId())).cacheControl(NO_STORE).body(created);
    }

    @GetMapping("/v1/ai/previews/{id}")
    public ResponseEntity<PreviewResponse> preview(@CurrentUser UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(PreviewResponse.of(getPreview.handle(userId, id)));
    }

    @PostMapping("/v1/ai/analyses")
    public ResponseEntity<AnalysisResponse> analyze(@CurrentUser UUID userId, @RequestHeader("Idempotency-Key") UUID idempotencyKey,
                                                    @Valid @RequestBody AnalysisInput body) {
        AnalysisResponse created = AnalysisResponse.of(runAnalysis.handle(new RunAnalysisCommand(userId, body.previewId(), body.approve(),
                idempotencyKey)));
        return ResponseEntity.created(URI.create("/v1/ai/analyses/" + created.id())).cacheControl(NO_STORE).body(created);
    }

    @GetMapping("/v1/ai/analyses")
    public ResponseEntity<SliceResponse<AnalysisSummaryResponse>> analyses(@CurrentUser UUID userId,
                                                                           @RequestParam(required = false) AiPurpose purpose,
                                                                           @RequestParam(required = false) AiStatus status, PageQuery page) {
        return ResponseEntity.ok().cacheControl(NO_STORE)
                .body(SliceResponse.of(listAnalyses.handle(userId, purpose, status, page), AnalysisSummaryResponse::of));
    }

    @GetMapping("/v1/ai/analyses/{id}")
    public ResponseEntity<AnalysisResponse> analysis(@CurrentUser UUID userId, @PathVariable UUID id) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(AnalysisResponse.of(getAnalysis.handle(userId, id)));
    }

    @GetMapping("/v1/settings/ai")
    public ResponseEntity<SettingsDto> settings(@CurrentUser UUID userId) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SettingsDto.of(getSettings.handle(userId)));
    }

    @PutMapping("/v1/settings/ai")
    public ResponseEntity<SettingsDto> saveSettings(@CurrentUser UUID userId, @Valid @RequestBody SettingsDto body) {
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SettingsDto.of(saveSettings.handle(userId, body.dailyMessageEnabled())));
    }
}
