package com.pulseops.otlp.decode;

import static com.pulseops.testsupport.OtlpTestData.DB_SPAN_ID;
import static com.pulseops.testsupport.OtlpTestData.SERVER_SPAN_ID;
import static com.pulseops.testsupport.OtlpTestData.TRACE_ID;
import static com.pulseops.testsupport.OtlpTestData.attr;
import static com.pulseops.testsupport.OtlpTestData.id;
import static com.pulseops.testsupport.OtlpTestData.javaAgentResource;
import static com.pulseops.testsupport.OtlpTestData.nanos;
import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.List;

import com.google.protobuf.ByteString;
import com.pulseops.processing.model.LogRecordData;
import com.pulseops.processing.model.MetricPointRecord;
import com.pulseops.processing.model.SpanRecord;
import com.pulseops.testsupport.MutableClock;
import com.pulseops.testsupport.OtlpTestData;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.ArrayValue;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;

import org.junit.jupiter.api.Test;

class OtlpDecoderTest {

    private static final Instant NOW = Instant.parse("2026-11-21T12:00:00Z");
    private static final Instant START = Instant.parse("2026-11-21T11:59:00Z");
    private final OtlpDecoder decoder = new OtlpDecoder(MutableClock.startingAt(NOW.toString()));

    @Test
    void decodesSpansWithKindStatusDurationAndRedactedAttributes() {
        OtlpDecoder.Decoded<SpanRecord> decoded = decoder.traces(
                OtlpTestData.traces(OtlpTestData.orderRequest(javaAgentResource("checkout"), START)));

        assertThat(decoded.invalid()).isZero();
        assertThat(decoded.resources()).hasSize(1);
        OtlpDecoder.ResourceItems<SpanRecord> resource = decoded.resources().get(0);
        assertThat(resource.serviceName()).isEqualTo("checkout");
        assertThat(resource.items()).hasSize(2);

        SpanRecord server = resource.items().get(0);
        assertThat(server.traceId()).isEqualTo(TRACE_ID);
        assertThat(server.spanId()).isEqualTo(SERVER_SPAN_ID);
        assertThat(server.parentSpanId()).isNull();
        assertThat(server.kind()).isEqualTo("SERVER");
        assertThat(server.statusCode()).isEqualTo("ERROR");
        assertThat(server.statusMessage()).isEqualTo("boom");
        assertThat(server.startTime()).isEqualTo(START);
        assertThat(server.durationMs()).isEqualTo(120.0);
        assertThat(server.attributes()).containsEntry("http.response.status_code", 500L)
                .containsEntry("http.request.header.authorization", "[REDACTED]");
        assertThat(server.events()).singleElement()
                .satisfies(e -> assertThat(e.name()).isEqualTo("exception"));

        SpanRecord db = resource.items().get(1);
        assertThat(db.parentSpanId()).isEqualTo(SERVER_SPAN_ID);
        assertThat(db.spanId()).isEqualTo(DB_SPAN_ID);
        assertThat(db.kind()).isEqualTo("CLIENT");
        assertThat(db.statusCode()).isEqualTo("UNSET");
        assertThat(db.attributes()).containsEntry("db.statement", "SELECT * FROM orders WHERE id = ?");
    }

    @Test
    void invalidSpansAreCountedNotFatal() {
        Span valid = Span.newBuilder().setTraceId(id(TRACE_ID)).setSpanId(id(SERVER_SPAN_ID)).setName("ok")
                .setStartTimeUnixNano(nanos(START)).setEndTimeUnixNano(nanos(START.plusMillis(1))).build();
        Span zeroTrace = valid.toBuilder().setTraceId(ByteString.copyFrom(new byte[16])).build();
        Span shortSpanId = valid.toBuilder().setSpanId(ByteString.copyFrom(new byte[]{1, 2, 3})).build();
        Span tooOld = valid.toBuilder().setStartTimeUnixNano(nanos(NOW.minus(Duration.ofDays(8)))).build();
        Span future = valid.toBuilder().setStartTimeUnixNano(nanos(NOW.plus(Duration.ofMinutes(10)))).build();
        Span noStart = valid.toBuilder().setStartTimeUnixNano(0).build();
        ExportTraceServiceRequest request = ExportTraceServiceRequest.newBuilder()
                .addResourceSpans(ResourceSpans.newBuilder().setResource(javaAgentResource("checkout"))
                        .addScopeSpans(ScopeSpans.newBuilder().addSpans(valid).addSpans(zeroTrace)
                                .addSpans(shortSpanId).addSpans(tooOld).addSpans(future).addSpans(noStart)))
                .build();

        OtlpDecoder.Decoded<SpanRecord> decoded = decoder.traces(request);

        assertThat(decoded.validCount()).isEqualTo(1);
        assertThat(decoded.invalid()).isEqualTo(5);
    }

    @Test
    void endBeforeStartGivesZeroDurationAndBlankNameIsReplaced() {
        Span span = Span.newBuilder().setTraceId(id(TRACE_ID)).setSpanId(id(SERVER_SPAN_ID))
                .setStartTimeUnixNano(nanos(START)).setEndTimeUnixNano(nanos(START.minusSeconds(1))).build();
        SpanRecord record = decoder.span(span, NOW);
        assertThat(record.durationMs()).isZero();
        assertThat(record.name()).isEqualTo("(unnamed)");
        assertThat(record.kind()).isEqualTo("INTERNAL");
    }

    @Test
    void anyValuesAreConverted() {
        assertThat(OtlpDecoder.value(AnyValue.newBuilder().setBoolValue(true).build())).isEqualTo(true);
        assertThat(OtlpDecoder.value(AnyValue.newBuilder().setDoubleValue(1.5).build())).isEqualTo(1.5);
        assertThat(OtlpDecoder.value(AnyValue.newBuilder().setArrayValue(ArrayValue.newBuilder()
                .addValues(AnyValue.newBuilder().setStringValue("a"))
                .addValues(AnyValue.newBuilder().setIntValue(2))).build())).isEqualTo(List.of("a", 2L));
        assertThat(OtlpDecoder.value(AnyValue.newBuilder().setBytesValue(ByteString.copyFrom(new byte[4])).build()))
                .isEqualTo("(4 bytes)");
        assertThat(OtlpDecoder.value(AnyValue.getDefaultInstance())).isNull();
    }

    @Test
    void decodesLogsWithSeverityTraceLinkAndRedaction() {
        OtlpDecoder.Decoded<LogRecordData> decoded = decoder.logs(OtlpTestData.logs(javaAgentResource("checkout"), START));

        assertThat(decoded.invalid()).isZero();
        LogRecordData log = decoded.resources().get(0).items().get(0);
        assertThat(log.level()).isEqualTo("WARN");
        assertThat(log.occurredAt()).isEqualTo(START);
        assertThat(log.message()).isEqualTo("Payment retry for card [REDACTED]");
        assertThat(log.traceId()).isEqualTo(TRACE_ID);
        assertThat(log.spanId()).isEqualTo(SERVER_SPAN_ID);
        assertThat(log.attributes()).containsEntry("password", "[REDACTED]");
    }

    @Test
    void decodesMetricsIntoOneMinuteAggregates() {
        Instant minute = Instant.parse("2026-11-21T11:58:00Z");
        OtlpDecoder.DecodedMetrics decoded = decoder.metrics(OtlpTestData.metrics(javaAgentResource("checkout"), minute));

        assertThat(decoded.invalidPoints()).isZero();
        List<MetricPointRecord> points = decoded.resources().get(0).items();
        assertThat(points).hasSize(2);
        MetricPointRecord gauge = points.stream().filter(p -> p.name().equals("jvm.memory.used")).findFirst().orElseThrow();
        assertThat(gauge.kind()).isEqualTo("gauge");
        assertThat(gauge.bucketStart()).isEqualTo(minute);
        assertThat(gauge.count()).isEqualTo(2);
        assertThat(gauge.sum()).isEqualTo(400.0);
        assertThat(gauge.min()).isEqualTo(100.0);
        assertThat(gauge.max()).isEqualTo(300.0);
        assertThat(gauge.last()).isEqualTo(300.0);
        assertThat(gauge.attributesKey()).isEqualTo("jvm.memory.type=heap");
        MetricPointRecord histogram = points.stream().filter(p -> p.kind().equals("histogram")).findFirst().orElseThrow();
        assertThat(histogram.count()).isEqualTo(10);
        assertThat(histogram.sum()).isEqualTo(1.5);
        assertThat(histogram.min()).isEqualTo(0.01);
        assertThat(histogram.max()).isEqualTo(0.9);
    }

    @Test
    void resourceWithoutServiceNameHasNullName() {
        ExportTraceServiceRequest request = OtlpTestData.traces(OtlpTestData.orderRequest(
                io.opentelemetry.proto.resource.v1.Resource.newBuilder().addAttributes(attr("host.name", "h1")).build(),
                START));
        assertThat(decoder.traces(request).resources().get(0).serviceName()).isNull();
    }
}
