package com.moneylog.infra.search.controller;

import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonInclude;

import com.moneylog.core.search.domain.SearchResults;
import com.moneylog.core.sk.money.Money;

public record SearchResponse(String q, List<TransactionHit> transactions, List<AccountHit> accounts, List<CategoryHit> categories,
                             List<CardHit> cards, List<NoteHit> notes, List<String> truncated) {

    public record Ref(UUID id, String name) {
    }

    public record TransactionHit(UUID id, String description, LocalDate date, Money amount, String type, Ref category, Ref account,
                                 @JsonInclude(JsonInclude.Include.NON_NULL) String incomeCompetence) {
    }

    public record AccountHit(UUID id, String name, String institution, String type, String status) {
    }

    public record CategoryHit(UUID id, String name, String kind, boolean archived) {
    }

    public record CardHit(UUID id, String name, String institution, String status) {
    }

    public record NoteHit(UUID id, String title, String path, String snippet) {
    }

    @Override
    public String toString() {
        return "SearchResponse[transactions=" + transactions.size() + ", accounts=" + accounts.size() + ", categories=" + categories.size()
                + ", cards=" + cards.size() + ", notes=" + notes.size() + ", truncated=" + truncated + "]";
    }

    public static SearchResponse of(SearchResults r) {
        return new SearchResponse(r.q(),
                r.transactions().stream().map(t -> new TransactionHit(t.id(), t.description(), t.date(), t.amount(), t.type().name(),
                        ref(t.category()), ref(t.account()), t.incomeCompetence())).toList(),
                r.accounts().stream().map(a -> new AccountHit(a.id(), a.name(), a.institution(), a.type().name(), a.status().name())).toList(),
                r.categories().stream().map(c -> new CategoryHit(c.id(), c.name(), c.kind().name(), c.archived())).toList(),
                r.cards().stream().map(c -> new CardHit(c.id(), c.name(), c.institution(), c.status().name())).toList(),
                r.notes().stream().map(n -> new NoteHit(n.id(), n.title(), n.path(), n.snippet())).toList(),
                r.truncated().stream().sorted(Comparator.naturalOrder()).map(g -> g.name().toLowerCase(java.util.Locale.ROOT)).toList());
    }

    private static Ref ref(SearchResults.Ref ref) {
        return ref == null ? null : new Ref(ref.id(), ref.name());
    }
}
