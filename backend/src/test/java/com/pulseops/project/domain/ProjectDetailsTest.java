package com.pulseops.project.domain;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class ProjectDetailsTest {

    private final ProjectDetails current = new ProjectDetails("Shop", "desc", "Spring", Environment.PRODUCTION,
            "ap-south-1", "web");

    @Test
    void nullFieldsInPatchKeepCurrentValues() {
        ProjectDetails merged = current.merge(new ProjectDetails(null, null, null, null, null, null));
        assertThat(merged).isEqualTo(current);
    }

    @Test
    void blankOptionalFieldsClearAndNamesAreTrimmed() {
        ProjectDetails merged = current.merge(new ProjectDetails("  New Shop ", "", " ", Environment.STAGING,
                null, "api"));
        assertThat(merged.name()).isEqualTo("New Shop");
        assertThat(merged.description()).isNull();
        assertThat(merged.techStack()).isNull();
        assertThat(merged.environment()).isEqualTo(Environment.STAGING);
        assertThat(merged.region()).isEqualTo("ap-south-1");
        assertThat(merged.applicationType()).isEqualTo("api");
    }

    @Test
    void normalizationDefaultsEnvironmentToProduction() {
        ProjectDetails normalized = new ProjectDetails(" Shop ", " ", null, null, "", null).normalized();
        assertThat(normalized.name()).isEqualTo("Shop");
        assertThat(normalized.description()).isNull();
        assertThat(normalized.region()).isNull();
        assertThat(normalized.environment()).isEqualTo(Environment.PRODUCTION);
    }
}
