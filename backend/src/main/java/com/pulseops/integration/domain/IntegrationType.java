package com.pulseops.integration.domain;

import com.pulseops.common.persistence.LowercaseEnumConverter;

public enum IntegrationType {
    /** OpenTelemetry Java agent exporting OTLP/HTTP. */
    JAVA_AGENT,
    /** PulseOps REST ingestion API (any language). */
    REST_API,
    /** Any OpenTelemetry SDK or Collector exporting OTLP/HTTP. */
    OTLP;

    @jakarta.persistence.Converter
    public static class JpaConverter extends LowercaseEnumConverter<IntegrationType> {
        public JpaConverter() {
            super(IntegrationType.class);
        }
    }
}
