package com.moneylog.core.search.app;

import com.moneylog.core.search.app.gateways.SearchGateway;
import com.moneylog.core.search.domain.SearchResults;

public class SearchUseCaseImpl implements SearchUseCase {

    private final SearchGateway gateway;

    public SearchUseCaseImpl(SearchGateway gateway) {
        this.gateway = gateway;
    }

    @Override
    public SearchResults handle(SearchQuery query) {
        return gateway.search(query.userId(), query.q(), query.limit());
    }
}
