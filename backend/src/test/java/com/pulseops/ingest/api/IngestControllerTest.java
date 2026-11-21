package com.pulseops.ingest.api;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class IngestControllerTest {

    @Test
    void keyIsTakenFromHeaderFirstThenBearer() {
        assertThat(IngestController.extractKey(" po_x ", "Bearer other")).isEqualTo("po_x");
        assertThat(IngestController.extractKey(null, "bearer po_y")).isEqualTo("po_y");
        assertThat(IngestController.extractKey("", "Bearer po_z")).isEqualTo("po_z");
    }

    @Test
    void otherSchemesAndMissingHeadersYieldNoKey() {
        assertThat(IngestController.extractKey(null, "Basic abc")).isNull();
        assertThat(IngestController.extractKey(null, null)).isNull();
    }
}
