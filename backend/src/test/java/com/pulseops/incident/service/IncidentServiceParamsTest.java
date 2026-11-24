package com.pulseops.incident.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;

class IncidentServiceParamsTest {

    @Test
    void statusFilterDefaultsToActive() {
        assertThat(IncidentService.statuses(null)).isEqualTo("open,investigating,acknowledged");
        assertThat(IncidentService.statuses(" Active ")).isEqualTo("open,investigating,acknowledged");
        assertThat(IncidentService.statuses("resolved")).isEqualTo("resolved");
        assertThat(IncidentService.statuses("all")).isEqualTo("open,investigating,acknowledged,resolved,closed");
        assertThatThrownBy(() -> IncidentService.statuses("dismissed")).isInstanceOf(ApiException.class);
    }

    @Test
    void severityIsValidated() {
        assertThat(IncidentService.severityRank(null)).isEqualTo(1);
        assertThat(IncidentService.severityRank("HIGH")).isEqualTo(3);
        assertThat(IncidentService.severity(" Critical ")).isEqualTo("critical");
        assertThatThrownBy(() -> IncidentService.severity("sev1")).isInstanceOf(ApiException.class);
        assertThatThrownBy(() -> IncidentService.severityRank("urgent")).isInstanceOf(ApiException.class);
    }
}
