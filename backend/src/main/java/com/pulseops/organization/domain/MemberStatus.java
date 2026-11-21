package com.pulseops.organization.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum MemberStatus {
    INVITED,
    ACTIVE,
    REMOVED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<MemberStatus> {
        public JpaConverter() {
            super(MemberStatus.class);
        }
    }
}
