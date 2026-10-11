package com.moneylog.core.search.app;

import java.util.UUID;

import com.moneylog.core.search.domain.SearchResults;
import com.moneylog.core.search.domain.SearchTerm;

public interface SearchUseCase {

    int MIN_LIMIT = 1;
    int MAX_LIMIT = 8;
    int DEFAULT_LIMIT = 5;

    SearchResults handle(SearchQuery query);

    record SearchQuery(UUID userId, String q, int limit) {

        public SearchQuery {
            q = SearchTerm.normalize(q).orElseThrow(() -> new IllegalArgumentException("termo de busca inválido"));
            if (limit < MIN_LIMIT || limit > MAX_LIMIT) {
                throw new IllegalArgumentException("limit fora de " + MIN_LIMIT + " a " + MAX_LIMIT);
            }
        }

        @Override
        public String toString() {
            return "SearchQuery[userId=" + userId + ", limit=" + limit + "]";
        }
    }
}
