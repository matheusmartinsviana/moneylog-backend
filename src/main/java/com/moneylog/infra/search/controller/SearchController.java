package com.moneylog.infra.search.controller;

import java.util.UUID;

import org.springframework.http.CacheControl;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.annotation.Validated;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;

import com.moneylog.core.search.app.SearchUseCase;
import com.moneylog.core.search.app.SearchUseCase.SearchQuery;
import com.moneylog.core.search.domain.SearchTerm;
import com.moneylog.infra.exception.InvalidPathValueException;
import com.moneylog.infra.security.CurrentUser;

/**
 * Busca global (redesign, S-287). O termo {@code q} pode ser dado financeiro ou pessoal: <b>nunca</b> vai para log (nem na mensagem de erro) e a
 * resposta não é cacheada. O limite de taxa vem antes de tudo (inclusive da validação), porque a rota roda a cada pausa de digitação.
 */
@RestController
@RequestMapping("/v1/search")
@Validated
public class SearchController {

    public static final String SEARCH_QUERY_INVALID = "SEARCH_QUERY_INVALID";
    private static final CacheControl NO_STORE = CacheControl.noStore().cachePrivate();

    private final SearchUseCase search;
    private final SearchRateLimiter rateLimiter;

    public SearchController(SearchUseCase search, SearchRateLimiter rateLimiter) {
        this.search = search;
        this.rateLimiter = rateLimiter;
    }

    @GetMapping
    public ResponseEntity<SearchResponse> get(@CurrentUser UUID userId, @RequestParam(required = false) String q,
                                              @RequestParam(defaultValue = "" + SearchUseCase.DEFAULT_LIMIT) @Min(SearchUseCase.MIN_LIMIT)
                                              @Max(SearchUseCase.MAX_LIMIT) int limit) {
        rateLimiter.acquire(userId);
        String term = SearchTerm.normalize(q).orElseThrow(() -> new InvalidPathValueException(SEARCH_QUERY_INVALID, "q",
                "Termo de busca inválido: use de " + SearchTerm.MIN_LENGTH + " a " + SearchTerm.MAX_LENGTH + " caracteres, sem caracteres de controle."));
        return ResponseEntity.ok().cacheControl(NO_STORE).body(SearchResponse.of(search.handle(new SearchQuery(userId, term, limit))));
    }
}
