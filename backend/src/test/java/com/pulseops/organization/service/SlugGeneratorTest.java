package com.pulseops.organization.service;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

class SlugGeneratorTest {

    /** Must always satisfy the organizations.slug CHECK constraint. */
    private static final String DB_SLUG_PATTERN = "^[a-z0-9][a-z0-9-]{1,62}[a-z0-9]$";

    private final SlugGenerator generator = new SlugGenerator();

    @ParameterizedTest
    @CsvSource({
            "Acme Corp, acme-corp",
            "  Café Déjà Vu!  , cafe-deja-vu",
            "E-Commerce   Platform, e-commerce-platform",
            "---, org",
            "日本語, org"
    })
    void buildsReadableBase(String name, String expectedBase) {
        assertThat(SlugGenerator.base(name)).isEqualTo(expectedBase);
    }

    @Test
    void truncatesLongNamesWithoutTrailingHyphen() {
        String base = SlugGenerator.base("a".repeat(39) + " " + "b".repeat(30));
        assertThat(base).hasSizeLessThanOrEqualTo(SlugGenerator.MAX_BASE).doesNotEndWith("-");
    }

    @Test
    void generatedSlugsSatisfyDatabaseConstraint() {
        for (String name : new String[]{"Acme", "x", "日本語", "a".repeat(200), "-weird--name-"}) {
            assertThat(generator.generate(name)).matches(DB_SLUG_PATTERN);
        }
    }

    @Test
    void generatedSlugsDifferForSameName() {
        assertThat(generator.generate("Acme")).isNotEqualTo(generator.generate("Acme"));
    }
}
