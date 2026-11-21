package com.pulseops.common.web;

import java.util.List;

/** Stable pagination envelope for list endpoints (0-based page index). */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <T> PageResponse<T> of(List<T> content, PageQuery query, long totalElements) {
        int totalPages = (int) Math.ceil(totalElements / (double) query.size());
        return new PageResponse<>(content, query.page(), query.size(), totalElements, totalPages);
    }
}
