package com.pulseops.otlp.mapping;

import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pulseops.processing.model.DbOperationRecord;
import com.pulseops.processing.model.EventType;
import com.pulseops.processing.model.LogRecordData;
import com.pulseops.processing.model.SpanRecord;
import com.pulseops.processing.model.TelemetryEvent;
import com.pulseops.processing.service.EventNormalizer;
import com.pulseops.processing.service.RouteTemplates;

/**
 * Derives monitoring data from spans, following OpenTelemetry semantic conventions (both the
 * current names and the older ones still emitted by many agents):
 * <ul>
 *   <li>SERVER spans with an HTTP method → API request (route template, method, status, duration)</li>
 *   <li>spans with {@code db.system(.name)} → database operation (statement shape only)</li>
 *   <li>span events named {@code exception} → ERROR log lines linked to the trace</li>
 * </ul>
 */
public final class SpanAnalyzer {

    private SpanAnalyzer() {
    }

    public record Derived(TelemetryEvent request, DbOperationRecord dbOperation, List<LogRecordData> exceptionLogs) {
    }

    public static Derived analyze(SpanRecord span) {
        return new Derived(request(span), dbOperation(span), exceptions(span));
    }

    static TelemetryEvent request(SpanRecord span) {
        if (!"SERVER".equals(span.kind())) {
            return null;
        }
        Map<String, Object> a = span.attributes();
        String method = OtlpAttributes.string(a, "http.request.method", "http.method");
        if (method == null) {
            return null;
        }
        String route = OtlpAttributes.string(a, "http.route");
        String template;
        if (route != null) {
            template = RouteTemplates.clean(route);
        } else {
            String path = OtlpAttributes.string(a, "url.path", "http.target");
            template = RouteTemplates.fromPath(path);
        }
        Long status = OtlpAttributes.integer(a, "http.response.status_code", "http.status_code");
        Integer statusCode = status != null && status >= 100 && status <= 599 ? status.intValue() : null;
        String normalizedMethod = EventNormalizer.normalizeMethod(method);
        TelemetryEvent.Http http = new TelemetryEvent.Http(normalizedMethod, template, statusCode,
                Math.max(0, span.durationMs()));
        return new TelemetryEvent(EventType.PERFORMANCE, normalizedMethod + " " + template, span.startTime(),
                Map.of(), http, null, null, span.traceId(), span.spanId());
    }

    static DbOperationRecord dbOperation(SpanRecord span) {
        Map<String, Object> a = span.attributes();
        String system = OtlpAttributes.string(a, "db.system.name", "db.system");
        if (system == null) {
            return null;
        }
        String statement = OtlpAttributes.string(a, "db.query.text", "db.statement");
        String operationName = OtlpAttributes.string(a, "db.operation.name", "db.operation");
        String shape = statement != null ? SqlSanitizer.sanitize(statement) : operationName;
        String table = OtlpAttributes.string(a, "db.collection.name", "db.sql.table");
        boolean error = "ERROR".equals(span.statusCode());
        return new DbOperationRecord(
                OtlpAttributes.truncate(system, 60),
                SqlSanitizer.operationType(operationName, statement),
                OtlpAttributes.truncate(shape, SqlSanitizer.MAX_LENGTH),
                OtlpAttributes.truncate(table, 200),
                Math.max(0, span.durationMs()),
                span.startTime(),
                error,
                error ? span.statusMessage() : null,
                span.traceId(),
                span.spanId());
    }

    static List<LogRecordData> exceptions(SpanRecord span) {
        List<LogRecordData> logs = new ArrayList<>();
        for (SpanRecord.SpanEvent event : span.events()) {
            if (!"exception".equals(event.name())) {
                continue;
            }
            Map<String, Object> a = event.attributes();
            String type = OtlpAttributes.string(a, "exception.type");
            String message = OtlpAttributes.string(a, "exception.message");
            String text = type == null ? (message == null ? "Exception" : message)
                    : (message == null ? type : type + ": " + message);
            Map<String, Object> attributes = new LinkedHashMap<>();
            if (type != null) {
                attributes.put("exception.type", type);
            }
            String stack = OtlpAttributes.string(a, "exception.stacktrace");
            if (stack != null) {
                attributes.put("exception.stacktrace", stack);
            }
            attributes.put("span.name", span.name());
            logs.add(new LogRecordData(event.time(), "ERROR", text, type, span.traceId(), span.spanId(), attributes));
        }
        return logs;
    }
}
