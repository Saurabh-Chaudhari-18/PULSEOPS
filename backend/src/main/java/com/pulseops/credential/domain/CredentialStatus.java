package com.pulseops.credential.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum CredentialStatus {
    ACTIVE,
    REVOKED,
    EXPIRED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<CredentialStatus> {
        public JpaConverter() {
            super(CredentialStatus.class);
        }
    }
}
