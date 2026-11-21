package com.pulseops.appservice.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

/** Runtime of a monitored service; drives which integration instructions are offered. */
public enum Technology {
    JAVA_SPRING_BOOT,
    PYTHON,
    NODEJS,
    DOTNET,
    GO,
    CUSTOM_REST,
    OPENTELEMETRY,
    OTHER;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<Technology> {
        public JpaConverter() {
            super(Technology.class);
        }
    }
}
