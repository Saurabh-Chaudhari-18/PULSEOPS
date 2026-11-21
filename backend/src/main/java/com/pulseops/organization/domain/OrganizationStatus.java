package com.pulseops.organization.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum OrganizationStatus {
    ACTIVE,
    SUSPENDED,
    DELETED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<OrganizationStatus> {
        public JpaConverter() {
            super(OrganizationStatus.class);
        }
    }
}
