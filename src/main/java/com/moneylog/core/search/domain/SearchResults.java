package com.moneylog.core.search.domain;

import java.time.LocalDate;
import java.util.List;
import java.util.Set;
import java.util.UUID;

import com.moneylog.core.account.domain.AccountStatus;
import com.moneylog.core.account.domain.AccountType;
import com.moneylog.core.card.domain.CardStatus;
import com.moneylog.core.category.domain.CategoryKind;
import com.moneylog.core.sk.money.Money;
import com.moneylog.core.transaction.domain.TransactionType;

/**
 * Resultado da busca global (redesign, S-287): só campos de exibição e de rota, nenhum total ou saldo agregado. {@code truncated} lista os
 * grupos cuja leitura estourou o limite de tempo (vêm vazios).
 */
public record SearchResults(String q, List<TransactionHit> transactions, List<AccountHit> accounts, List<CategoryHit> categories,
                            List<CardHit> cards, List<NoteHit> notes, Set<SearchGroup> truncated) {

    public SearchResults {
        transactions = List.copyOf(transactions);
        accounts = List.copyOf(accounts);
        categories = List.copyOf(categories);
        cards = List.copyOf(cards);
        notes = List.copyOf(notes);
        truncated = Set.copyOf(truncated);
    }

    /** Sem o termo nem o texto dos resultados (S-287: {@code q} fora do log). */
    @Override
    public String toString() {
        return "SearchResults[transactions=" + transactions.size() + ", accounts=" + accounts.size() + ", categories=" + categories.size()
                + ", cards=" + cards.size() + ", notes=" + notes.size() + ", truncated=" + truncated + "]";
    }

    public record Ref(UUID id, String name) {
    }

    public record TransactionHit(UUID id, String description, LocalDate date, Money amount, TransactionType type, Ref category, Ref account,
                                 String incomeCompetence) {

        /** Sem competência de renda (todos os tipos, menos a receita de outro mês, ADR-017). */
        public TransactionHit(UUID id, String description, LocalDate date, Money amount, TransactionType type, Ref category, Ref account) {
            this(id, description, date, amount, type, category, account, null);
        }
    }

    public record AccountHit(UUID id, String name, String institution, AccountType type, AccountStatus status) {
    }

    public record CategoryHit(UUID id, String name, CategoryKind kind, boolean archived) {
    }

    public record CardHit(UUID id, String name, String institution, CardStatus status) {
    }

    /** {@code snippet}: texto puro (nunca Markdown nem HTML). */
    public record NoteHit(UUID id, String title, String path, String snippet) {
    }
}
