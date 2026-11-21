package com.pulseops.project.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

/** Deployment environment of a monitored project; values match the projects.environment CHECK. */
public enum Environment {
    DEVELOPMENT,
    TESTING,
    STAGING,
    PRODUCTION,
    OTHER;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<Environment> {
        public JpaConverter() {
            super(Environment.class);
        }
    }
}
