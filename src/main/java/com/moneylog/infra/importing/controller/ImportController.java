package com.moneylog.infra.importing.controller;

import java.io.IOException;
import java.net.URI;
import java.util.UUID;

import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import jakarta.validation.Valid;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.ObjectMapper;

import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCase;
import com.moneylog.core.finance.app.imports.ApplyImportBatchUseCase.ApplyImportBatchCommand;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCase;
import com.moneylog.core.finance.app.imports.UndoImportBatchUseCase.UndoImportBatchCommand;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.CreateImportBatchUseCase.CreateImportBatchCommand;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.DeleteCategorizationRuleUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.DiscardImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.GetImportBatchUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.GetImportMappingUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListCategorizationRulesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListImportBatchesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.ListImportLinesUseCase;
import com.moneylog.core.importing.app.usecase.ImportQueryUseCases.SaveImportMappingUseCase;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkReviewCommand;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.BulkReviewImportLinesUseCase;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.ReviewImportLineCommand;
import com.moneylog.core.importing.app.usecase.ReviewImportLinesUseCases.ReviewImportLineUseCase;
import com.moneylog.core.importing.domain.ImportBatchStatus;
import com.moneylog.core.importing.domain.ImportLineFlag;
import com.moneylog.core.importing.domain.ImportLineStatus;
import com.moneylog.core.importing.parser.ColumnMapping;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.importing.controller.ImportRequests.BulkInput;
import com.moneylog.infra.importing.controller.ImportRequests.LinePatch;
import com.moneylog.infra.importing.controller.ImportRequests.MappingInput;
import com.moneylog.infra.importing.controller.ImportRequests.UndoInput;
import com.moneylog.infra.importing.controller.ImportResponses.AppliedResponse;
import com.moneylog.infra.importing.controller.ImportResponses.BatchResponse;
import com.moneylog.infra.importing.controller.ImportResponses.BulkResponse;
import com.moneylog.infra.importing.controller.ImportResponses.LineResponse;
import com.moneylog.infra.importing.controller.ImportResponses.RuleResponse;
import com.moneylog.infra.importing.controller.ImportResponses.UndoneResponse;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.SliceResponse;

/**
 * Importação de extrato CSV/OFX (Etapa 7): o upload só <b>prepara</b> o lote; nada vira movimentação até {@code apply}. Todo endpoint filtra pelo
 * usuário do token. Escritas devolvem o recurso (upload: {@code 201} + {@code Location}).
 */
@RestController
public class ImportController {

    private final CreateImportBatchUseCase create;
    private final GetImportBatchUseCase get;
    private final ListImportBatchesUseCase list;
    private final ListImportLinesUseCase lines;
    private final ReviewImportLineUseCase review;
    private final BulkReviewImportLinesUseCase bulk;
    private final ApplyImportBatchUseCase apply;
    private final UndoImportBatchUseCase undo;
    private final GetImportMappingUseCase getMapping;
    private final SaveImportMappingUseCase saveMapping;
    private final ListCategorizationRulesUseCase listRules;
    private final DeleteCategorizationRuleUseCase deleteRule;
    private final DiscardImportBatchUseCase discard;
    private final ObjectMapper mapper;

    @SuppressWarnings("java:S107")
    public ImportController(CreateImportBatchUseCase create, GetImportBatchUseCase get, ListImportBatchesUseCase list, ListImportLinesUseCase lines,
                            ReviewImportLineUseCase review, BulkReviewImportLinesUseCase bulk, ApplyImportBatchUseCase apply,
                            UndoImportBatchUseCase undo, GetImportMappingUseCase getMapping, SaveImportMappingUseCase saveMapping,
                            ListCategorizationRulesUseCase listRules, DeleteCategorizationRuleUseCase deleteRule, DiscardImportBatchUseCase discard,
                            ObjectMapper mapper) {
        this.discard = discard;
        this.create = create;
        this.get = get;
        this.list = list;
        this.lines = lines;
        this.review = review;
        this.bulk = bulk;
        this.apply = apply;
        this.undo = undo;
        this.getMapping = getMapping;
        this.saveMapping = saveMapping;
        this.listRules = listRules;
        this.deleteRule = deleteRule;
        this.mapper = mapper;
    }

    @PostMapping(value = "/v1/imports", consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    public ResponseEntity<BatchResponse> upload(@CurrentUser UUID userId, @RequestPart("file") org.springframework.web.multipart.MultipartFile file,
                                                @RequestParam(required = false) UUID accountId, @RequestParam(required = false) UUID cardId,
                                                @RequestParam(required = false) String format, @RequestParam(required = false) String mapping,
                                                @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey)
            throws IOException {
        if ((accountId == null) == (cardId == null)) {
            throw new RequestValidationException("accountId", "XOR_REQUIRED", "Informe accountId ou cardId (apenas um).");
        }
        if (format != null && !format.equalsIgnoreCase("CSV") && !format.equalsIgnoreCase("OFX")) {
            throw new RequestValidationException("format", "INVALID_FORMAT", "format deve ser CSV ou OFX.");
        }
        BatchResponse body = BatchResponse.of(create.handle(new CreateImportBatchCommand(userId, accountId, cardId, file.getBytes(), format,
                parseMapping(mapping), idempotencyKey)));
        return ResponseEntity.created(URI.create("/v1/imports/" + body.id())).body(body);
    }

    private ColumnMapping parseMapping(String json) {
        if (json == null || json.isBlank()) {
            return null;
        }
        try {
            return mapper.readValue(json, MappingInput.class).toDomain();
        } catch (JacksonException e) {
            throw new RequestValidationException("mapping", "INVALID_FORMAT", "O mapeamento precisa ser um JSON válido.");
        }
    }

    @GetMapping("/v1/imports")
    public SliceResponse<BatchResponse> list(@CurrentUser UUID userId, @RequestParam(required = false) ImportBatchStatus status, PageQuery page) {
        return SliceResponse.of(list.handle(userId, status, page), BatchResponse::of);
    }

    @GetMapping("/v1/imports/{id}")
    public BatchResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return BatchResponse.of(get.handle(userId, id));
    }

    @GetMapping("/v1/imports/{id}/lines")
    public SliceResponse<LineResponse> lines(@CurrentUser UUID userId, @PathVariable UUID id, @RequestParam(required = false) ImportLineStatus status,
                                             @RequestParam(required = false) ImportLineFlag flag, PageQuery page) {
        return SliceResponse.of(lines.handle(userId, id, status, flag, page), LineResponse::of);
    }

    @PatchMapping("/v1/imports/{id}/lines/{lineId}")
    public LineResponse patchLine(@CurrentUser UUID userId, @PathVariable UUID id, @PathVariable UUID lineId, @Valid @RequestBody LinePatch body) {
        return LineResponse.of(review.handle(new ReviewImportLineCommand(userId, id, lineId, body.action(), body.categoryId(),
                body.method() == null ? null : body.method().name(), body.description(),
                body.asTransfer() == null ? null : body.asTransfer().counterpartAccountId(), Boolean.TRUE.equals(body.learn()))));
    }

    @PostMapping("/v1/imports/{id}/lines:bulk")
    public BulkResponse bulk(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody BulkInput body) {
        return BulkResponse.of(bulk.handle(new BulkReviewCommand(userId, id, body.lineIds(), body.action())));
    }

    @PostMapping("/v1/imports/{id}/apply")
    public AppliedResponse apply(@CurrentUser UUID userId, @PathVariable UUID id,
                                 @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey) {
        return AppliedResponse.of(apply.handle(new ApplyImportBatchCommand(userId, id, idempotencyKey)));
    }

    /** Descarta um lote ainda em revisão (nada foi aplicado). Lote aplicado/desfeito: 422. */
    @DeleteMapping("/v1/imports/{id}")
    public ResponseEntity<Void> discardByDelete(@CurrentUser UUID userId, @PathVariable UUID id) {
        discard.handle(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/imports/{id}/discard")
    public ResponseEntity<Void> discard(@CurrentUser UUID userId, @PathVariable UUID id) {
        discard.handle(userId, id);
        return ResponseEntity.noContent().build();
    }

    @PostMapping("/v1/imports/{id}/undo")
    public UndoneResponse undo(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody(required = false) UndoInput body) {
        return UndoneResponse.of(undo.handle(new UndoImportBatchCommand(userId, id, body == null ? null : body.reason())));
    }

    @GetMapping("/v1/import-mappings/{sourceId}")
    public MappingInput getMapping(@CurrentUser UUID userId, @PathVariable UUID sourceId) {
        return MappingInput.of(getMapping.handle(userId, sourceId));
    }

    @PutMapping("/v1/import-mappings/{sourceId}")
    public MappingInput putMapping(@CurrentUser UUID userId, @PathVariable UUID sourceId, @Valid @RequestBody MappingInput body) {
        return MappingInput.of(saveMapping.handle(userId, sourceId, body.toDomain()));
    }

    @GetMapping("/v1/categorization-rules")
    public SliceResponse<RuleResponse> rules(@CurrentUser UUID userId, PageQuery page) {
        return SliceResponse.of(listRules.handle(userId, page), RuleResponse::of);
    }

    @DeleteMapping("/v1/categorization-rules/{id}")
    public ResponseEntity<Void> deleteRule(@CurrentUser UUID userId, @PathVariable UUID id) {
        deleteRule.handle(userId, id);
        return ResponseEntity.noContent().build();
    }
}
