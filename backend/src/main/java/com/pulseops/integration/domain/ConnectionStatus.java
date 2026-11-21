package com.pulseops.integration.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

/**
 * Connection lifecycle. CONNECTED is only ever set by the ingestion pipeline after
 * telemetry has actually been accepted — never by issuing a credential or picking a method.
 */
public enum ConnectionStatus {
    NOT_CONNECTED,
    WAITING_FOR_DATA,
    CONNECTED,
    CONNECTION_ERROR,
    DISABLED;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<ConnectionStatus> {
        public JpaConverter() {
            super(ConnectionStatus.class);
        }
    }
}
