package com.pulseops.common.persistence;

import static org.assertj.core.api.Assertions.assertThat;

import com.pulseops.user.domain.UserStatus;

import org.junit.jupiter.api.Test;

class LowercaseEnumConverterTest {

    private final UserStatus.JpaConverter converter = new UserStatus.JpaConverter();

    @Test
    void mapsToDatabaseCheckConstraintValues() {
        assertThat(converter.convertToDatabaseColumn(UserStatus.PENDING_VERIFICATION)).isEqualTo("pending_verification");
        assertThat(converter.convertToEntityAttribute("active")).isEqualTo(UserStatus.ACTIVE);
    }

    @Test
    void passesNullThrough() {
        assertThat(converter.convertToDatabaseColumn(null)).isNull();
        assertThat(converter.convertToEntityAttribute(null)).isNull();
    }
}
