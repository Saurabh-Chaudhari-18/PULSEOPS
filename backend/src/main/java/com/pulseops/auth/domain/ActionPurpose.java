package com.pulseops.auth.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum ActionPurpose {
    EMAIL_VERIFICATION,
    PASSWORD_RESET;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<ActionPurpose> {
        public JpaConverter() {
            super(ActionPurpose.class);
        }
    }
}
