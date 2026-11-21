package com.pulseops.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LikePatternsTest {

    @Test
    void wrapsAndEscapesWildcards() {
        assertThat(LikePatterns.contains("order")).isEqualTo("%order%");
        assertThat(LikePatterns.contains(" 50%_off ")).isEqualTo("%50\\%\\_off%");
        assertThat(LikePatterns.contains("a\\b")).isEqualTo("%a\\\\b%");
    }

    @Test
    void blankMeansNoFilter() {
        assertThat(LikePatterns.contains(null)).isNull();
        assertThat(LikePatterns.contains("   ")).isNull();
    }
}
