package com.pulseops.project.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum ProjectStatus {
    ACTIVE,
    ARCHIVED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<ProjectStatus> {
        public JpaConverter() {
            super(ProjectStatus.class);
        }
    }
}
