package com.pulseops.testsupport;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.UncheckedIOException;
import java.time.Instant;
import java.util.HexFormat;
import java.util.zip.GZIPOutputStream;

import com.google.protobuf.ByteString;

import io.opentelemetry.proto.collector.logs.v1.ExportLogsServiceRequest;
import io.opentelemetry.proto.collector.metrics.v1.ExportMetricsServiceRequest;
import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceRequest;
import io.opentelemetry.proto.common.v1.AnyValue;
import io.opentelemetry.proto.common.v1.KeyValue;
import io.opentelemetry.proto.logs.v1.LogRecord;
import io.opentelemetry.proto.logs.v1.ResourceLogs;
import io.opentelemetry.proto.logs.v1.ScopeLogs;
import io.opentelemetry.proto.logs.v1.SeverityNumber;
import io.opentelemetry.proto.metrics.v1.Gauge;
import io.opentelemetry.proto.metrics.v1.Histogram;
import io.opentelemetry.proto.metrics.v1.HistogramDataPoint;
import io.opentelemetry.proto.metrics.v1.Metric;
import io.opentelemetry.proto.metrics.v1.NumberDataPoint;
import io.opentelemetry.proto.metrics.v1.ResourceMetrics;
import io.opentelemetry.proto.metrics.v1.ScopeMetrics;
import io.opentelemetry.proto.resource.v1.Resource;
import io.opentelemetry.proto.trace.v1.ResourceSpans;
import io.opentelemetry.proto.trace.v1.ScopeSpans;
import io.opentelemetry.proto.trace.v1.Span;
import io.opentelemetry.proto.trace.v1.Status;

/** Builders for realistic OTLP requests, shaped like what the OpenTelemetry Java agent sends. */
public final class OtlpTestData {

    public static final String TRACE_ID = "4bf92f3577b34da6a3ce929d0e0e4736";
    public static final String SERVER_SPAN_ID = "00f067aa0ba902b7";
    public static final String DB_SPAN_ID = "00f067aa0ba902b8";

    private OtlpTestData() {
    }

    public static KeyValue attr(String key, String value) {
        return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setStringValue(value)).build();
    }

    public static KeyValue attr(String key, long value) {
        return KeyValue.newBuilder().setKey(key).setValue(AnyValue.newBuilder().setIntValue(value)).build();
    }

    public static ByteString id(String hex) {
        return ByteString.copyFrom(HexFormat.of().parseHex(hex));
    }

    public static long nanos(Instant t) {
        return t.getEpochSecond() * 1_000_000_000L + t.getNano();
    }

    /** A resource as the Java agent describes it. */
    public static Resource javaAgentResource(String serviceName) {
        return Resource.newBuilder()
                .addAttributes(attr("service.name", serviceName))
                .addAttributes(attr("telemetry.sdk.language", "java"))
                .addAttributes(attr("telemetry.distro.name", "opentelemetry-java-instrumentation"))
                .addAttributes(attr("process.command_line", "java -jar app.jar --db.password=hunter2"))
                .build();
    }

    /** SERVER span (GET /orders/{id} → 500, with an exception event) + child PostgreSQL CLIENT span. */
    public static ResourceSpans orderRequest(Resource resource, Instant start) {
        Span server = Span.newBuilder()
                .setTraceId(id(TRACE_ID)).setSpanId(id(SERVER_SPAN_ID))
                .setName("GET /orders/{id}").setKind(Span.SpanKind.SPAN_KIND_SERVER)
                .setStartTimeUnixNano(nanos(start)).setEndTimeUnixNano(nanos(start.plusMillis(120)))
                .addAttributes(attr("http.request.method", "GET"))
                .addAttributes(attr("http.route", "/orders/{id}"))
                .addAttributes(attr("url.path", "/orders/12345"))
                .addAttributes(attr("http.response.status_code", 500L))
                .addAttributes(attr("http.request.header.authorization", "Bearer secret-token"))
                .addEvents(Span.Event.newBuilder().setName("exception")
                        .setTimeUnixNano(nanos(start.plusMillis(110)))
                        .addAttributes(attr("exception.type", "java.sql.SQLTransientConnectionException"))
                        .addAttributes(attr("exception.message", "HikariPool-1 - Connection is not available"))
                        .addAttributes(attr("exception.stacktrace", "at com.zaxxer.hikari.pool.HikariPool.getConnection")))
                .setStatus(Status.newBuilder().setCode(Status.StatusCode.STATUS_CODE_ERROR).setMessage("boom"))
                .build();
        Span db = Span.newBuilder()
                .setTraceId(id(TRACE_ID)).setSpanId(id(DB_SPAN_ID)).setParentSpanId(id(SERVER_SPAN_ID))
                .setName("SELECT shop.orders").setKind(Span.SpanKind.SPAN_KIND_CLIENT)
                .setStartTimeUnixNano(nanos(start.plusMillis(5))).setEndTimeUnixNano(nanos(start.plusMillis(105)))
                .addAttributes(attr("db.system", "postgresql"))
                .addAttributes(attr("db.statement", "SELECT * FROM orders WHERE id = 12345"))
                .addAttributes(attr("db.sql.table", "orders"))
                .build();
        return ResourceSpans.newBuilder().setResource(resource)
                .addScopeSpans(ScopeSpans.newBuilder().addSpans(server).addSpans(db))
                .build();
    }

    public static ExportTraceServiceRequest traces(ResourceSpans... resources) {
        ExportTraceServiceRequest.Builder b = ExportTraceServiceRequest.newBuilder();
        for (ResourceSpans rs : resources) {
            b.addResourceSpans(rs);
        }
        return b.build();
    }

    public static ExportLogsServiceRequest logs(Resource resource, Instant time) {
        LogRecord record = LogRecord.newBuilder()
                .setTimeUnixNano(nanos(time))
                .setSeverityNumber(SeverityNumber.SEVERITY_NUMBER_WARN)
                .setSeverityText("WARN")
                .setBody(AnyValue.newBuilder().setStringValue("Payment retry for card 4111 1111 1111 1111"))
                .setTraceId(id(TRACE_ID)).setSpanId(id(SERVER_SPAN_ID))
                .addAttributes(attr("password", "hunter2"))
                .build();
        return ExportLogsServiceRequest.newBuilder()
                .addResourceLogs(ResourceLogs.newBuilder().setResource(resource)
                        .addScopeLogs(ScopeLogs.newBuilder().addLogRecords(record)))
                .build();
    }

    /** Two gauge samples in one minute plus a request-duration histogram. */
    public static ExportMetricsServiceRequest metrics(Resource resource, Instant minuteStart) {
        Metric gauge = Metric.newBuilder().setName("jvm.memory.used").setUnit("By")
                .setGauge(Gauge.newBuilder()
                        .addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(nanos(minuteStart.plusSeconds(10)))
                                .setAsInt(100).addAttributes(attr("jvm.memory.type", "heap")))
                        .addDataPoints(NumberDataPoint.newBuilder().setTimeUnixNano(nanos(minuteStart.plusSeconds(40)))
                                .setAsInt(300).addAttributes(attr("jvm.memory.type", "heap"))))
                .build();
        Metric histogram = Metric.newBuilder().setName("http.server.request.duration").setUnit("s")
                .setHistogram(Histogram.newBuilder()
                        .addDataPoints(HistogramDataPoint.newBuilder()
                                .setTimeUnixNano(nanos(minuteStart.plusSeconds(30)))
                                .setCount(10).setSum(1.5).setMin(0.01).setMax(0.9)
                                .addAttributes(attr("http.route", "/orders/{id}"))))
                .build();
        return ExportMetricsServiceRequest.newBuilder()
                .addResourceMetrics(ResourceMetrics.newBuilder().setResource(resource)
                        .addScopeMetrics(ScopeMetrics.newBuilder().addMetrics(gauge).addMetrics(histogram)))
                .build();
    }

    public static byte[] gzip(byte[] data) {
        ByteArrayOutputStream out = new ByteArrayOutputStream();
        try (GZIPOutputStream gz = new GZIPOutputStream(out)) {
            gz.write(data);
        } catch (IOException e) {
            throw new UncheckedIOException(e);
        }
        return out.toByteArray();
    }
}
