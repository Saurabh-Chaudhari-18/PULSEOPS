package com.pulseops.common.web;

/**
 * Validated page request. Size is clamped so no endpoint can be asked for an
 * unbounded result set.
 */
public record PageQuery(int page, int size) {

    public static final int DEFAULT_SIZE = 25;
    public static final int MAX_SIZE = 100;

    public PageQuery {
        page = Math.max(page, 0);
        size = size <= 0 ? DEFAULT_SIZE : Math.min(size, MAX_SIZE);
    }

    public static PageQuery of(Integer page, Integer size) {
        return new PageQuery(page == null ? 0 : page, size == null ? DEFAULT_SIZE : size);
    }

    public long offset() {
        return (long) page * size;
    }
}
