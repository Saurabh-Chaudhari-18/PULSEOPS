package com.pulseops.otlp.mapping;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

import com.pulseops.processing.model.SpanRecord;

import org.junit.jupiter.api.Test;

/**
 * Protocol-free OTLP interpretation: SQL sanitizing, attribute limits and redaction, span
 * analysis (requests, DB calls, exceptions), severity mapping and metric downsampling.
 */
class OtlpMappingTest {

    private static SpanRecord span(String kind, Map<String, Object> raw, String status, List<SpanRecord.SpanEvent> ev) {
        return new SpanRecord("4bf92f3577b34da6a3ce929d0e0e4736", "00f067aa0ba902b7", null, "op", kind,
                Instant.parse("2026-11-21T10:00:30Z"), 42.5, status, status.equals("ERROR") ? "boom" : null,
                OtlpAttributes.sanitize(raw), ev);
    }

    @Test
    void sqlSanitizer() {
        assertThat((Object) (SqlSanitizer.sanitize("SELECT * FROM users WHERE email = 'a@b.com' AND id = 42")))
                .as("sql literals").isEqualTo("SELECT * FROM users WHERE email = ? AND id = ?");
        assertThat((Object) (SqlSanitizer.sanitize("select * from t where id in (1, 2, 3) and name='O''Brien'")))
                .as("sql in-list + escaped quote").isEqualTo("select * from t where id in (?) and name=?");
        assertThat((Object) (SqlSanitizer.sanitize("UPDATE t2 SET col1 = 3.5e2 WHERE v1 = 0xFF")))
                .as("sql identifiers with digits kept").isEqualTo("UPDATE t2 SET col1 = ? WHERE v1 = ?");
        assertThat((Object) (SqlSanitizer.sanitize("  SELECT\n  1 ")))
                .as("sql whitespace").isEqualTo("SELECT ?");
        assertThat((Object) (SqlSanitizer.operationType(null, "  select x from y")))
                .as("op from statement").isEqualTo("SELECT");
        assertThat((Object) (SqlSanitizer.operationType("INSERT", null)))
                .as("op from name").isEqualTo("INSERT");
        assertThat((Object) (SqlSanitizer.operationType("find", null)))
                .as("mongo find").isEqualTo("OTHER");
        assertThat((Object) (SqlSanitizer.operationType(null, "WITH c AS (select 1) select * from c")))
                .as("CTE").isEqualTo("SELECT");
        assertThat((Object) (SqlSanitizer.operationType(null, "create table x(id int)")))
                .as("ddl").isEqualTo("DDL");
    }

    @Test
    void attributes() {
        Map<String,Object> raw = new LinkedHashMap<>();
        raw.put("http.request.header.authorization", List.of("Bearer abc"));
        raw.put("db.statement", "SELECT * FROM cards WHERE number = '4111111111111111'");
        raw.put("process.command_line", "java -jar app.jar --password hunter2");
        raw.put("url.query", "q=shoes&api_key=SECRET123");
        raw.put("http.response.status_code", 200L);
        raw.put("exception.stacktrace", "x".repeat(5000));
        raw.put("bad.double", Double.NaN);
        Map<String,Object> s = OtlpAttributes.sanitize(raw);
        assertThat((Object) (s.get("http.request.header.authorization")))
                .as("auth header redacted").isEqualTo("[REDACTED]");
        assertThat((Object) (s.get("db.statement")))
                .as("sql attribute sanitized").isEqualTo("SELECT * FROM cards WHERE number = ?");
        assertThat((Object) (s.containsKey("process.command_line")))
                .as("command line dropped").isEqualTo(false);
        assertThat((Object) (s.get("url.query")))
                .as("query secret scrubbed").isEqualTo("q=shoes&api_key=[REDACTED]");
        assertThat((Object) (s.get("http.response.status_code")))
                .as("number kept").isEqualTo(200L);
        assertThat((Object) (((String)s.get("exception.stacktrace")).length()))
                .as("stack trace keeps 5000 chars (limit 16000)").isEqualTo(5000);
        assertThat((Object) (s.containsKey("bad.double")))
                .as("NaN dropped").isEqualTo(false);
        Map<String,Object> many=new LinkedHashMap<>();
        for(int i=0;i<100;i++) many.put("k"+i,i);
        assertThat((Object) (OtlpAttributes.sanitize(many).size()))
                .as("attribute cap").isEqualTo(64);
    }

    @Test
    void serverAndClientSpans() {
        var d = SpanAnalyzer.analyze(span("SERVER", Map.of("http.request.method","GET","http.route","/orders/{id}","http.response.status_code",503L), "ERROR", List.of()));
        assertThat((Object) (d.request().http().route()))
                .as("server route").isEqualTo("/orders/{id}");
        assertThat((Object) (d.request().http().statusClass()))
                .as("server status").isEqualTo("5xx");
        assertThat((Object) (d.request().http().durationMs()))
                .as("server duration").isEqualTo(42.5);
        assertThat((Object) (d.dbOperation()))
                .as("no db op on server span").isEqualTo(null);
        var old = SpanAnalyzer.analyze(span("SERVER", Map.of("http.method","post","http.target","/carts/12345/items?x=1","http.status_code","201"), "UNSET", List.of()));
        assertThat((Object) (old.request().http().route()))
                .as("old semconv path templated").isEqualTo("/carts/{id}/items");
        assertThat((Object) (old.request().http().method()))
                .as("old semconv method").isEqualTo("POST");
        assertThat((Object) (old.request().http().statusCode()))
                .as("string status parsed").isEqualTo(201);
        assertThat((Object) (SpanAnalyzer.analyze(span("CLIENT", Map.of("http.request.method","GET"), "UNSET", List.of())).request()))
                .as("client http span is not an API request").isEqualTo(null);
    }

    @Test
    void databaseSpans() {
        var db = SpanAnalyzer.analyze(span("CLIENT", Map.of("db.system","postgresql","db.statement","SELECT * FROM orders WHERE id = 7","db.sql.table","orders"), "ERROR", List.of())).dbOperation();
        assertThat((Object) (db.dbSystem()))
                .as("db system").isEqualTo("postgresql");
        assertThat((Object) (db.operationType()))
                .as("db op type").isEqualTo("SELECT");
        assertThat((Object) (db.operation()))
                .as("db statement shape").isEqualTo("SELECT * FROM orders WHERE id = ?");
        assertThat((Object) (db.tableName()))
                .as("db table").isEqualTo("orders");
        assertThat((Object) (db.error()))
                .as("db error").isEqualTo(true);
        assertThat((Object) (db.errorMessage()))
                .as("db error message").isEqualTo("boom");
        var db2 = SpanAnalyzer.analyze(span("CLIENT", Map.of("db.system.name","redis","db.operation.name","GET"), "OK", List.of())).dbOperation();
        assertThat((Object) (db2.operationType()))
                .as("redis GET is OTHER").isEqualTo("OTHER");
        assertThat((Object) (db2.operation()))
                .as("op name used as shape").isEqualTo("GET");
    }

    @Test
    void exceptionEvents() {
        var ex = new SpanRecord.SpanEvent("exception", Instant.parse("2026-11-21T10:00:31Z"), OtlpAttributes.sanitize(Map.of("exception.type","java.sql.SQLTransientConnectionException","exception.message","HikariPool-1 - Connection is not available, request timed out after 30000ms","exception.stacktrace","at x.y(Z.java:1)")));
        var logs = SpanAnalyzer.analyze(span("SERVER", Map.of("http.method","GET","http.route","/a"), "ERROR", List.of(ex, new SpanRecord.SpanEvent("other", Instant.now(), Map.of())))).exceptionLogs();
        assertThat((Object) (logs.size()))
                .as("one exception log").isEqualTo(1);
        assertThat((Object) (logs.get(0).level()))
                .as("exception level").isEqualTo("ERROR");
        assertThat((Object) (logs.get(0).message()))
                .as("exception message").isEqualTo("java.sql.SQLTransientConnectionException: HikariPool-1 - Connection is not available, request timed out after 30000ms");
        assertThat((Object) (logs.get(0).traceId()))
                .as("exception linked to trace").isEqualTo("4bf92f3577b34da6a3ce929d0e0e4736");
        assertThat((Object) (logs.get(0).attributes().get("exception.stacktrace")))
                .as("stack kept").isEqualTo("at x.y(Z.java:1)");
    }

    @Test
    void logLevels() {
        assertThat((Object) (LogLevels.fromSeverity(9,null)))
                .as("sev 9").isEqualTo("INFO");
        assertThat((Object) (LogLevels.fromSeverity(13,null)))
                .as("sev 13").isEqualTo("WARN");
        assertThat((Object) (LogLevels.fromSeverity(17,null)))
                .as("sev 17").isEqualTo("ERROR");
        assertThat((Object) (LogLevels.fromSeverity(21,null)))
                .as("sev 21").isEqualTo("FATAL");
        assertThat((Object) (LogLevels.fromSeverity(0,"warning")))
                .as("text warning").isEqualTo("WARN");
        assertThat((Object) (LogLevels.fromSeverity(0,"SEVERE")))
                .as("jul severe").isEqualTo("ERROR");
        assertThat((Object) (LogLevels.fromSeverity(0,null)))
                .as("default info").isEqualTo("INFO");
        assertThat((Object) (LogLevels.fromSeverity(5,"ERROR")))
                .as("number wins").isEqualTo("DEBUG");
    }

    @Test
    void metricAggregation() {
        MetricPointAggregator m = new MetricPointAggregator();
        Map<String,Object> pool = Map.of("jvm.memory.pool.name","G1 Eden Space","jvm.memory.type","heap");
        m.addValue("jvm.memory.used","By","gauge",pool,Instant.parse("2026-11-21T10:00:10Z"),100);
        m.addValue("jvm.memory.used","By","gauge",Map.of("jvm.memory.type","heap","jvm.memory.pool.name","G1 Eden Space"),Instant.parse("2026-11-21T10:00:40Z"),300);
        m.addValue("jvm.memory.used","By","gauge",pool,Instant.parse("2026-11-21T10:00:20Z"),200);
        m.addValue("jvm.memory.used","By","gauge",pool,Instant.parse("2026-11-21T10:01:05Z"),50);
        m.addDistribution("http.server.request.duration","s","histogram",Map.of(),Instant.parse("2026-11-21T10:00:00Z"),10,1.5,0.01,0.9);
        m.addValue("x","1","gauge",Map.of(),Instant.now(),Double.NaN);
        var r = m.results();
        assertThat((Object) (r.size()))
                .as("series per minute (attribute order irrelevant)").isEqualTo(3);
        var first = r.get(0);
        assertThat((Object) (first.count()+"/"+first.sum()+"/"+first.min()+"/"+first.max()+"/"+first.last()))
                .as("gauge min/max/sum/last-by-time").isEqualTo("3/600.0/100.0/300.0/300.0");
        assertThat((Object) (first.attributesKey()))
                .as("canonical key sorted").isEqualTo("jvm.memory.pool.name=G1 Eden Space,jvm.memory.type=heap");
        assertThat((Object) (r.get(2).count()+"/"+r.get(2).sum()))
                .as("histogram count/sum").isEqualTo("10/1.5");
        assertThat((Object) (m.droppedPoints()))
                .as("NaN dropped").isEqualTo(1);
        MetricPointAggregator cap = new MetricPointAggregator();
        for(int i=0;i<2005;i++) cap.addValue("m","1","gauge",Map.of("id",i),Instant.parse("2026-11-21T10:00:00Z"),1);
        assertThat((Object) (cap.results().size()+"/"+cap.droppedPoints()))
                .as("cardinality cap").isEqualTo("2000/5");
    }
}
