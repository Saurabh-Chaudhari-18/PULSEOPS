package com.pulseops.common.persistence;

import java.util.Locale;

import jakarta.persistence.AttributeConverter;

/**
 * Maps a Java enum (UPPER_SNAKE) to the lowercase snake_case values enforced by the
 * database CHECK constraints, e.g. {@code PENDING_VERIFICATION <-> 'pending_verification'}.
 * Subclass per enum with a public no-arg constructor and annotate with {@code @Converter}.
 */
public abstract class LowercaseEnumConverter<E extends Enum<E>> implements AttributeConverter<E, String> {

    private final Class<E> type;

    protected LowercaseEnumConverter(Class<E> type) {
        this.type = type;
    }

    @Override
    public String convertToDatabaseColumn(E value) {
        return value == null ? null : value.name().toLowerCase(Locale.ROOT);
    }

    @Override
    public E convertToEntityAttribute(String dbValue) {
        return dbValue == null ? null : Enum.valueOf(type, dbValue.toUpperCase(Locale.ROOT));
    }
}
