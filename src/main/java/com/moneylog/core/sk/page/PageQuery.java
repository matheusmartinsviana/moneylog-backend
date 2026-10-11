package com.moneylog.core.sk.page;

import com.moneylog.core.sk.exception.BusinessRuleException;

public record PageQuery(int page, int pageSize) {

    public static final int DEFAULT_PAGE_SIZE = 10;
    public static final int MAX_PAGE_SIZE = 50;

    public PageQuery {
        if (page < 1 || pageSize < 1 || pageSize > MAX_PAGE_SIZE) {
            throw new InvalidPageQueryException(page, pageSize);
        }
    }

    public static PageQuery first() {
        return new PageQuery(1, DEFAULT_PAGE_SIZE);
    }

    public long offset() {
        return (long) (page - 1) * pageSize;
    }

    public boolean isBeyondAnyData() {
        return offset() > Integer.MAX_VALUE;
    }

    public int fetchSize() {
        return pageSize + 1;
    }

    public static class InvalidPageQueryException extends BusinessRuleException {
        public InvalidPageQueryException(int page, int pageSize) {
            super("INVALID_PAGE_QUERY", "Paginação inválida: page=" + page + ", pageSize=" + pageSize
                    + ". Use page ≥ 1 e pageSize entre 1 e " + MAX_PAGE_SIZE + ".");
        }
    }
}
