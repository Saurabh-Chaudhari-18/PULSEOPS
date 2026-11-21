package com.pulseops.otlp.decode;

import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.HexFormat;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.google.protobuf.ByteString;
import com.pulseops.otlp.mapping.LogLevels;
import com.pulseops.otlp.mapping.MetricPointAggregator;
import com.pulseops.otlp.mapping.OtlpAttributes;
import com.pulseops.processing.model.LogRecordData;
import com.pulseops.processing.model.MetricPointRecord;
import com.pulseops.processing.model.SpanRecord;
import com.pulseops.processing.service.SensitiveDataRedactor;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.metrics.v1.ExponentialHistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.metrics.v1.SummaryDataPoint;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;

import org.springframework.stereotype.Component;

/**
 * Translates OTLP protobuf requests into protocol-independent records, validating and
 * sanitizing as it goes. Invalid items (bad IDs, timestamps outside the accepted window) are
 * counted, not fatal: OTLP's partial-success response reports them to the exporter.
 *
 * <p>All interpretation logic lives in the protobuf-free {@code otlp.mapping} package; this
 * class only reads protobuf fields.</p>
 */
@Component
public class OtlpDecoder {

    static final Duration MAX_AGE = Duration.ofDays(7);
    static final Duration MAX_FUTURE_SKEW = Duration.ofMinutes(5);
    static final int MAX_NAME = 300;
    static final int MAX_LOG_MESSAGE = 8_000;
    static final int MAX_STATUS_MESSAGE = 500;
    private static final HexFormat HEX = HexFormat.of();

    private final Clock clock;

    public OtlpDecoder(Clock clock) {
        this.clock = clock;
    }

    /** Items of one OTLP resource (= one emitting service instance). */
    public record ResourceItems<T>(Map<String, Object> resource, List<T> items) {

        public String serviceName() {
            Object name = resource.get("service.name");
            return name == null ? null : String.valueOf(name);
        }
    }

    public record Decoded<T>(List<ResourceItems<T>> resources, int invalid) {

        public int validCount() {
            return resources.stream().mapToInt(r -> r.items().size()).sum();
        }
    }

    // ---------------------------------------------------------------------------------- traces

    public Decoded<SpanRecord> traces(ExportTraceServiceRequest request) {
        Instant now = clock.instant();
        List<ResourceItems<SpanRecord>> out = new ArrayList<>();
        int invalid = 0;
        for (ResourceSpans rs : request.getResourceSpansList()) {
            List<SpanRecord> spans = new ArrayList<>();
            for (ScopeSpans ss : rs.getScopeSpansList()) {
                for (Span span : ss.getSpansList()) {
                    SpanRecord record = span(span, now);
                    if (record == null) {
                        invalid++;
                    } else {
                        spans.add(record);
                    }
                }
            }
            if (!spans.isEmpty()) {
                out.add(new ResourceItems<>(attributes(rs.getResource().getAttributesList()), spans));
            }
        }
        return new Decoded<>(out, invalid);
    }

    SpanRecord span(Span span, Instant now) {
        String traceId = hexId(span.getTraceId(), 16);
        String spanId = hexId(span.getSpanId(), 8);
        Instant start = fromNanos(span.getStartTimeUnixNano());
        if (traceId == null || spanId == null || start == null || !withinWindow(start, now)) {
            return null;
        }
        String parent = span.getParentSpanId().isEmpty() ? null : hexId(span.getParentSpanId(), 8);
        long durationNanos = Math.max(0, span.getEndTimeUnixNano() - span.getStartTimeUnixNano());
        List<SpanRecord.SpanEvent> events = new ArrayList<>();
        for (Span.Event e : span.getEventsList()) {
            Instant t = fromNanos(e.getTimeUnixNano());
            events.add(new SpanRecord.SpanEvent(e.getName(), t == null ? start : t,
                    OtlpAttributes.sanitize(attributes(e.getAttributesList()))));
        }
        String name = span.getName().isBlank() ? "(unnamed)" : truncate(span.getName(), MAX_NAME);
        String statusMessage = span.getStatus().getMessage().isBlank() ? null
                : SensitiveDataRedactor.scrub(truncate(span.getStatus().getMessage(), MAX_STATUS_MESSAGE));
        return new SpanRecord(traceId, spanId, parent, name, kind(span.getKind()), start,
                durationNanos / 1_000_000.0, statusCode(span.getStatus().getCode()), statusMessage,
                OtlpAttributes.sanitize(attributes(span.getAttributesList())), events);
    }

    static String kind(Span.SpanKind kind) {
        return switch (kind) {
            case SPAN_KIND_SERVER -> "SERVER";
            case SPAN_KIND_CLIENT -> "CLIENT";
            case SPAN_KIND_PRODUCER -> "PRODUCER";
            case SPAN_KIND_CONSUMER -> "CONSUMER";
            default -> "INTERNAL";
        };
    }

    static String statusCode(Status.StatusCode code) {
        return switch (code) {
            case STATUS_CODE_OK -> "OK";
            case STATUS_CODE_ERROR -> "ERROR";
            default -> "UNSET";
        };
    }

    // ------------------------------------------------------------------------------------ logs

    public Decoded<LogRecordData> logs(ExportLogsServiceRequest request) {
        Instant now = clock.instant();
        List<ResourceItems<LogRecordData>> out = new ArrayList<>();
        int invalid = 0;
        for (ResourceLogs rl : request.getResourceLogsList()) {
            List<LogRecordData> logs = new ArrayList<>();
            for (ScopeLogs sl : rl.getScopeLogsList()) {
                String scope = sl.getScope().getName().isBlank() ? null : truncate(sl.getScope().getName(), 200);
                for (LogRecord lr : sl.getLogRecordsList()) {
                    LogRecordData record = log(lr, scope, now);
                    if (record == null) {
                        invalid++;
                    } else {
                        logs.add(record);
                    }
                }
            }
            if (!logs.isEmpty()) {
                out.add(new ResourceItems<>(attributes(rl.getResource().getAttributesList()), logs));
            }
        }
        return new Decoded<>(out, invalid);
    }

    LogRecordData log(LogRecord lr, String scope, Instant now) {
        Instant time = fromNanos(lr.getTimeUnixNano());
        if (time == null) {
            time = fromNanos(lr.getObservedTimeUnixNano());
        }
        if (time == null || !withinWindow(time, now)) {
            return null;
        }
        Object body = value(lr.getBody());
        String message = body == null ? "" : String.valueOf(body);
        message = SensitiveDataRedactor.scrub(truncate(message, MAX_LOG_MESSAGE));
        if (message.isBlank()) {
            message = "(empty)";
        }
        String traceId = lr.getTraceId().isEmpty() ? null : hexId(lr.getTraceId(), 16);
        String spanId = lr.getSpanId().isEmpty() ? null : hexId(lr.getSpanId(), 8);
        return new LogRecordData(time, LogLevels.fromSeverity(lr.getSeverityNumberValue(), lr.getSeverityText()),
                message, scope, traceId, spanId, OtlpAttributes.sanitize(attributes(lr.getAttributesList())));
    }

    // --------------------------------------------------------------------------------- metrics

    public record DecodedMetrics(List<ResourceItems<MetricPointRecord>> resources, int invalidPoints) {
    }

    public DecodedMetrics metrics(ExportMetricsServiceRequest request) {
        Instant now = clock.instant();
        List<ResourceItems<MetricPointRecord>> out = new ArrayList<>();
        int invalid = 0;
        for (ResourceMetrics rm : request.getResourceMetricsList()) {
            MetricPointAggregator agg = new MetricPointAggregator();
            for (ScopeMetrics sm : rm.getScopeMetricsList()) {
                for (Metric metric : sm.getMetricsList()) {
                    invalid += addMetric(agg, metric, now);
                }
            }
            invalid += agg.droppedPoints();
            List<MetricPointRecord> points = agg.results();
            if (!points.isEmpty()) {
                out.add(new ResourceItems<>(attributes(rm.getResource().getAttributesList()), points));
            }
        }
        return new DecodedMetrics(out, invalid);
    }

    /** @return number of points rejected for invalid timestamps */
    private int addMetric(MetricPointAggregator agg, Metric metric, Instant now) {
        String name = metric.getName();
        String unit = metric.getUnit();
        int invalid = 0;
        switch (metric.getDataCase()) {
            case GAUGE -> {
                for (NumberDataPoint p : metric.getGauge().getDataPointsList()) {
                    invalid += addNumber(agg, name, unit, "gauge", p, now);
                }
            }
            case SUM -> {
                for (NumberDataPoint p : metric.getSum().getDataPointsList()) {
                    invalid += addNumber(agg, name, unit, "sum", p, now);
                }
            }
            case HISTOGRAM -> {
                for (HistogramDataPoint p : metric.getHistogram().getDataPointsList()) {
                    Instant t = fromNanos(p.getTimeUnixNano());
                    if (t == null || !withinWindow(t, now)) {
                        invalid++;
                        continue;
                    }
                    agg.addDistribution(name, unit, "histogram", attributes(p.getAttributesList()), t, p.getCount(),
                            p.hasSum() ? p.getSum() : 0, p.hasMin() ? p.getMin() : null, p.hasMax() ? p.getMax() : null);
                }
            }
            case EXPONENTIAL_HISTOGRAM -> {
                for (ExponentialHistogramDataPoint p : metric.getExponentialHistogram().getDataPointsList()) {
                    Instant t = fromNanos(p.getTimeUnixNano());
                    if (t == null || !withinWindow(t, now)) {
                        invalid++;
                        continue;
                    }
                    agg.addDistribution(name, unit, "histogram", attributes(p.getAttributesList()), t, p.getCount(),
                            p.hasSum() ? p.getSum() : 0, p.hasMin() ? p.getMin() : null, p.hasMax() ? p.getMax() : null);
                }
            }
            case SUMMARY -> {
                for (SummaryDataPoint p : metric.getSummary().getDataPointsList()) {
                    Instant t = fromNanos(p.getTimeUnixNano());
                    if (t == null || !withinWindow(t, now)) {
                        invalid++;
                        continue;
                    }
                    agg.addDistribution(name, unit, "summary", attributes(p.getAttributesList()), t, p.getCount(),
                            p.getSum(), null, null);
                }
            }
            default -> {
                // DATA_NOT_SET: nothing to record
            }
        }
        return invalid;
    }

    private int addNumber(MetricPointAggregator agg, String name, String unit, String kind, NumberDataPoint p,
                          Instant now) {
        Instant t = fromNanos(p.getTimeUnixNano());
        if (t == null || !withinWindow(t, now)) {
            return 1;
        }
        double value = p.getValueCase() == NumberDataPoint.ValueCase.AS_INT ? p.getAsInt() : p.getAsDouble();
        agg.addValue(name, unit, kind, attributes(p.getAttributesList()), t, value);
        return 0;
    }

    // --------------------------------------------------------------------------------- helpers

    static Map<String, Object> attributes(List<KeyValue> keyValues) {
        Map<String, Object> out = new LinkedHashMap<>();
        for (KeyValue kv : keyValues) {
            Object v = value(kv.getValue());
            if (v != null) {
                out.put(kv.getKey(), v);
            }
        }
        return out;
    }

    static Object value(AnyValue v) {
        return switch (v.getValueCase()) {
            case STRING_VALUE -> v.getStringValue();
            case BOOL_VALUE -> v.getBoolValue();
            case INT_VALUE -> v.getIntValue();
            case DOUBLE_VALUE -> v.getDoubleValue();
            case ARRAY_VALUE -> {
                List<Object> list = new ArrayList<>();
                for (AnyValue item : v.getArrayValue().getValuesList()) {
                    Object o = value(item);
                    if (o != null) {
                        list.add(o);
                    }
                }
                yield list;
            }
            case KVLIST_VALUE -> attributes(v.getKvlistValue().getValuesList()).toString();
            case BYTES_VALUE -> "(" + v.getBytesValue().size() + " bytes)";
            default -> null;
        };
    }

    /** Lowercase hex of an ID of exactly {@code bytes} bytes; null if absent, wrong size or all zero. */
    static String hexId(ByteString id, int bytes) {
        if (id == null || id.size() != bytes) {
            return null;
        }
        byte[] raw = id.toByteArray();
        boolean allZero = true;
        for (byte b : raw) {
            if (b != 0) {
                allZero = false;
                break;
            }
        }
        return allZero ? null : HEX.formatHex(raw);
    }

    static Instant fromNanos(long epochNanos) {
        if (epochNanos <= 0) {
            return null;
        }
        return Instant.ofEpochSecond(epochNanos / 1_000_000_000L, epochNanos % 1_000_000_000L);
    }

    private static boolean withinWindow(Instant t, Instant now) {
        return !t.isBefore(now.minus(MAX_AGE)) && !t.isAfter(now.plus(MAX_FUTURE_SKEW));
    }

    private static String truncate(String s, int max) {
        return s.length() <= max ? s : s.substring(0, max);
    }
}
