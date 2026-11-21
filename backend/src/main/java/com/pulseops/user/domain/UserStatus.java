package com.pulseops.user.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum UserStatus {
    PENDING_VERIFICATION,
    ACTIVE,
    DISABLED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<UserStatus> {
        public JpaConverter() {
            super(UserStatus.class);
        }
    }
}
