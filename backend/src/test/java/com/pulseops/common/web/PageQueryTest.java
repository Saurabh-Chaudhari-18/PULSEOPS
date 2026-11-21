package com.pulseops.common.web;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;

import org.junit.jupiter.api.Test;

class PageQueryTest {

    @Test
    void clampsSizeAndPage() {
        assertThat(PageQuery.of(-3, 10_000)).isEqualTo(new PageQuery(0, PageQuery.MAX_SIZE));
        assertThat(PageQuery.of(null, null)).isEqualTo(new PageQuery(0, PageQuery.DEFAULT_SIZE));
        assertThat(PageQuery.of(2, 0).size()).isEqualTo(PageQuery.DEFAULT_SIZE);
    }

    @Test
    void computesOffsetAndTotalPages() {
        PageQuery q = PageQuery.of(2, 10);
        assertThat(q.offset()).isEqualTo(20);
        assertThat(PageResponse.of(List.of(), q, 21).totalPages()).isEqualTo(3);
        assertThat(PageResponse.of(List.of(), q, 0).totalPages()).isZero();
    }
}
