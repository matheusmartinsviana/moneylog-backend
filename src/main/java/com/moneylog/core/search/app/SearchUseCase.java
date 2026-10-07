package com.moneylog.core.search.app;

import java.util.UUID;

import com.moneylog.core.search.domain.SearchResults;
import com.moneylog.core.search.domain.SearchTerm;

public interface SearchUseCase {

    int MIN_LIMIT = 1;
    int MAX_LIMIT = 8;
    int DEFAULT_LIMIT = 5;

    /** Busca global sem diferenciar acento nem caixa; leitura pura, só do usuário do token. */
    SearchResults handle(SearchQuery query);

    /** @param q o termo; é aparado e validado ({@link SearchTerm}): inválido é erro de programação (a borda web já recusou com 400) */
    record SearchQuery(UUID userId, String q, int limit) {

        public SearchQuery {
            q = SearchTerm.normalize(q).orElseThrow(() -> new IllegalArgumentException("termo de busca inválido"));
            if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit fora de " + MIN_LIMIT + " a " + MAX_LIMIT);
            }
        }

        /** Sem o termo (S-287: {@code q} fora do log). */
        @Override
        public String toString() {
            return "SearchQuery[userId=" + userId + ", limit=" + limit + "]";
        }
    }
}
