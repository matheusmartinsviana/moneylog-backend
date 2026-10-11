package com.moneylog.core.search.app.gateways;

import java.util.UUID;

import com.moneylog.core.search.domain.SearchResults;

public interface SearchGateway {

    SearchResults search(UUID userId, String q, int limit);
}
