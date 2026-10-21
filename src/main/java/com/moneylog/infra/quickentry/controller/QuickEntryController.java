package com.moneylog.infra.quickentry.controller;

import java.net.URI;

import org.springframework.http.HttpHeaders;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.validation.Valid;

import com.moneylog.core.quickentry.app.CreateQuickEntryResult;
import com.moneylog.core.quickentry.app.CreateQuickEntryUseCase;
import com.moneylog.core.quickentry.domain.QuickIdempotencyKey;
import com.moneylog.infra.exception.RequestValidationException;
import com.moneylog.infra.security.ClientIpResolver;
import com.moneylog.infra.security.QuickTokenPrincipal;

@RestController
@RequestMapping(QuickEntryController.PATH)
public class QuickEntryController implements QuickEntryControllerOpenApi {

    public static final String PATH = "/v1/quick-entries";
    public static final String IDEMPOTENCY_HEADER = "Idempotency-Key";

    private final CreateQuickEntryUseCase create;
    private final ClientIpResolver clientIp;

    public QuickEntryController(CreateQuickEntryUseCase create, ClientIpResolver clientIp) {
        this.create = create;
        this.clientIp = clientIp;
    }

    @Override
    @PostMapping
    public ResponseEntity<QuickEntryResponse> create(@AuthenticationPrincipal QuickTokenPrincipal principal,
                                                     @RequestHeader(name = IDEMPOTENCY_HEADER, required = false) String idempotencyKey,
                                                     @Valid @RequestBody QuickEntryRequest body, HttpServletRequest request) {
        requireValidKey(idempotencyKey);
        if (body.amountOutOfSize()) {
            throw new RequestValidationException("amount", "SIZE", "Valor com tamanho inválido.");
        }
        if (body.methodOnIncome()) {
            throw new RequestValidationException("method", "NOT_ALLOWED", "Forma de pagamento só vale para gasto.");
        }
        CreateQuickEntryResult created = create.handle(body.toCommand(principal.userId(), principal.tokenId(), principal.accountId(),
                idempotencyKey, clientIp.resolve(request)));
        QuickEntryResponse response = QuickEntryResponse.of(created);
        return ResponseEntity.created(URI.create("/v1/transactions/" + response.id())).header(HttpHeaders.CACHE_CONTROL, "no-store")
                .body(response);
    }

    private static void requireValidKey(String key) {
        if (key == null) {
            throw new RequestValidationException(IDEMPOTENCY_HEADER, "REQUIRED", "Cabeçalho obrigatório.");
        }
        if (!QuickIdempotencyKey.isValid(key)) {
            throw new RequestValidationException(IDEMPOTENCY_HEADER, "INVALID_FORMAT", "Formato inválido.");
        }
    }
}
