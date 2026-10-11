package com.moneylog.core.sk.ledger;

import java.util.UUID;

import com.moneylog.core.sk.ledger.LedgerExceptions.InvalidPostingException;

public record BookRef(BookType type, UUID id) {

    public BookRef {
        if (type == null) {
            throw new InvalidPostingException("o tipo do livro é obrigatório.");
        }
        if (type.requiresId() && id == null) {
            throw new InvalidPostingException("o livro " + type + " exige id.");
        }
        if (!type.requiresId() && id != null) {
            throw new InvalidPostingException("o livro " + type + " não tem id.");
        }
    }

    public static BookRef account(UUID accountId) {
        return new BookRef(BookType.ACCOUNT, accountId);
    }

    public static BookRef card(UUID cardId) {
        return new BookRef(BookType.CARD, cardId);
    }

    public static BookRef receivable(UUID debtId) {
        return new BookRef(BookType.RECEIVABLE, debtId);
    }

    public static BookRef payable(UUID debtId) {
        return new BookRef(BookType.PAYABLE, debtId);
    }

    public static BookRef expense(UUID categoryId) {
        return new BookRef(BookType.EXPENSE, categoryId);
    }

    public static BookRef income(UUID categoryId) {
        return new BookRef(BookType.INCOME, categoryId);
    }

    public static BookRef equity() {
        return new BookRef(BookType.EQUITY, null);
    }

    @Override
    public String toString() {
        return id == null ? type.name() : type + "(" + id + ")";
    }
}
