package com.moneylog.infra.persistence;

import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;

import com.moneylog.core.sk.page.PageQuery;

public final class OffsetLimit implements Pageable {

    private final long offset;
    private final int limit;

    private OffsetLimit(long offset, int limit) {
        this.offset = offset;
        this.limit = limit;
    }

    public static OffsetLimit of(PageQuery query) {
        return new OffsetLimit(query.offset(), query.fetchSize());
    }

    @Override
    public int getPageNumber() {
        return (int) (offset / limit);
    }

    @Override
    public int getPageSize() {
        return limit;
    }

    @Override
    public long getOffset() {
        return offset;
    }

    @Override
    public Sort getSort() {
        return Sort.unsorted();
    }

    @Override
    public Pageable next() {
        return new OffsetLimit(offset + limit, limit);
    }

    @Override
    public Pageable previousOrFirst() {
        return hasPrevious() ? new OffsetLimit(Math.max(0, offset - limit), limit) : first();
    }

    @Override
    public Pageable first() {
        return new OffsetLimit(0, limit);
    }

    @Override
    public Pageable withPage(int pageNumber) {
        return new OffsetLimit((long) pageNumber * limit, limit);
    }

    @Override
    public boolean hasPrevious() {
        return offset > 0;
    }
}
