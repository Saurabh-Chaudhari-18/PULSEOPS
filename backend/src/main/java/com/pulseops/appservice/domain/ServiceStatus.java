package com.pulseops.appservice.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum ServiceStatus {
    ACTIVE,
    ARCHIVED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<ServiceStatus> {
        public JpaConverter() {
            super(ServiceStatus.class);
        }
    }
}
