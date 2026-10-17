package com.moneylog.infra.income.controller;

import java.net.URI;
import java.util.UUID;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.Valid;

import com.moneylog.core.income.app.usecase.ArchiveIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.ArchiveIncomeProfileUseCase.ArchiveIncomeProfileCommand;
import com.moneylog.core.income.app.usecase.CreateIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.CreateIncomeProfileUseCase.CreateIncomeProfileCommand;
import com.moneylog.core.income.app.usecase.GenerateIncomeOccurrencesUseCase;
import com.moneylog.core.income.app.usecase.GenerateIncomeOccurrencesUseCase.GenerateIncomeOccurrencesCommand;
import com.moneylog.core.income.app.usecase.GetIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.GetIncomeProfileUseCase.GetIncomeProfileQuery;
import com.moneylog.core.income.app.usecase.ListIncomeProfilesUseCase;
import com.moneylog.core.income.app.usecase.ListIncomeProfilesUseCase.ListIncomeProfilesQuery;
import com.moneylog.core.income.app.usecase.PreviewIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.PreviewIncomeProfileUseCase.PreviewIncomeProfileQuery;
import com.moneylog.core.income.app.usecase.UpdateIncomeProfileUseCase;
import com.moneylog.core.income.app.usecase.UpdateIncomeProfileUseCase.UpdateIncomeProfileCommand;
import com.moneylog.core.income.domain.IncomeProfile;
import com.moneylog.core.sk.page.PageQuery;
import com.moneylog.core.sk.persistence.Versioned;
import com.moneylog.infra.income.controller.IncomeProfileRequests.IncomeProfileInput;
import com.moneylog.infra.income.controller.IncomeProfileRequests.IncomeProfileUpdate;
import com.moneylog.infra.security.CurrentUser;
import com.moneylog.infra.web.QueryMonths;
import com.moneylog.infra.web.SliceResponse;

@RestController
@RequestMapping("/v1/income-profiles")
public class IncomeProfileController {

    public static final String INCOME_MONTH_INVALID = "INCOME_MONTH_INVALID";

    private final CreateIncomeProfileUseCase create;
    private final UpdateIncomeProfileUseCase update;
    private final ArchiveIncomeProfileUseCase archive;
    private final GetIncomeProfileUseCase get;
    private final ListIncomeProfilesUseCase list;
    private final PreviewIncomeProfileUseCase preview;
    private final GenerateIncomeOccurrencesUseCase generate;

    @SuppressWarnings("java:S107")
    public IncomeProfileController(CreateIncomeProfileUseCase create, UpdateIncomeProfileUseCase update,
                                   ArchiveIncomeProfileUseCase archive, GetIncomeProfileUseCase get, ListIncomeProfilesUseCase list,
                                   PreviewIncomeProfileUseCase preview, GenerateIncomeOccurrencesUseCase generate) {
        this.create = create;
        this.update = update;
        this.archive = archive;
        this.get = get;
        this.list = list;
        this.preview = preview;
        this.generate = generate;
    }

    @GetMapping
    public SliceResponse<IncomeProfileResponse> list(@CurrentUser UUID userId,
                                                     @RequestParam(required = false) IncomeProfileResponse.Status status,
                                                     PageQuery page) {
        boolean archived = status == IncomeProfileResponse.Status.ARCHIVED;
        return SliceResponse.of(list.handle(new ListIncomeProfilesQuery(userId, archived, page)), IncomeProfileResponse::of);
    }

    @PostMapping
    public ResponseEntity<IncomeProfileResponse> create(@CurrentUser UUID userId,
                                                        @RequestHeader(value = "Idempotency-Key", required = false) UUID idempotencyKey,
                                                        @Valid @RequestBody IncomeProfileInput body) {
        Versioned<IncomeProfile> created = create.handle(new CreateIncomeProfileCommand(userId, body.toSpec(), idempotencyKey));
        return ResponseEntity.created(URI.create("/v1/income-profiles/" + created.value().id())).body(IncomeProfileResponse.of(created));
    }

    @GetMapping("/{id}")
    public IncomeProfileResponse get(@CurrentUser UUID userId, @PathVariable UUID id) {
        return IncomeProfileResponse.of(get.handle(new GetIncomeProfileQuery(userId, id)));
    }

    @PutMapping("/{id}")
    public IncomeProfileResponse update(@CurrentUser UUID userId, @PathVariable UUID id, @Valid @RequestBody IncomeProfileUpdate body) {
        return IncomeProfileResponse.of(update.handle(new UpdateIncomeProfileCommand(userId, id, body.toSpec(), body.version())));
    }

    @PostMapping("/{id}/archive")
    public IncomeProfileResponse archive(@CurrentUser UUID userId, @PathVariable UUID id) {
        return IncomeProfileResponse.of(archive.handle(new ArchiveIncomeProfileCommand(userId, id)));
    }

    @GetMapping("/{id}/preview")
    public IncomePreviewResponse preview(@CurrentUser UUID userId, @PathVariable UUID id, @RequestParam String month) {
        return IncomePreviewResponse.of(preview.handle(new PreviewIncomeProfileQuery(userId, id,
                QueryMonths.parse(month, INCOME_MONTH_INVALID, "month"))));
    }

    @PostMapping("/{id}/generate-occurrences")
    public GeneratedIncomeOccurrencesResponse generateOccurrences(@CurrentUser UUID userId, @PathVariable UUID id,
                                                                  @RequestParam String from, @RequestParam String to) {
        return GeneratedIncomeOccurrencesResponse.of(generate.handle(new GenerateIncomeOccurrencesCommand(userId, id,
                QueryMonths.parse(from, INCOME_MONTH_INVALID, "from"), QueryMonths.parse(to, INCOME_MONTH_INVALID, "to"))));
    }
}
