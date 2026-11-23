package com.pulseops;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.UUID;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

import jakarta.servlet.http.Cookie;

import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import com.pulseops.common.tenancy.TenantContext;
import com.pulseops.testsupport.OtlpTestData;
import com.pulseops.testsupport.TestProperties;

import io.opentelemetry.proto.collector.trace.v1.ExportTraceServiceResponse;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.condition.EnabledIfEnvironmentVariable;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

/**
 * End-to-end customer journey against a real PostgreSQL, with the FULL application context:
 * Flyway migrations, Hibernate schema validation, both security filter chains, RLS tenant
 * wiring, the async telemetry processor and every module from Phases 3–6.
 *
 * <p>Runs only when PULSEOPS_TEST_DB_URL points at a disposable database (the app role must
 * NOT be a superuser or BYPASSRLS). Every run uses fresh random names, so it can be repeated.</p>
 */
@SpringBootTest
@AutoConfigureMockMvc
@EnabledIfEnvironmentVariable(named = "PULSEOPS_TEST_DB_URL", matches = ".+")
class FullJourneyIntegrationTest {

    private static final String PASSWORD = "correct horse battery staple";
    private static final Pattern REFRESH_COOKIE = Pattern.compile("pulseops_refresh=([^;]+)");

    @Autowired
    MockMvc mvc;
    @Autowired
    ObjectMapper json;
    @Autowired
    JdbcTemplate jdbc;

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", () -> System.getenv("PULSEOPS_TEST_DB_URL"));
        registry.add("spring.datasource.username",
                () -> System.getenv().getOrDefault("PULSEOPS_TEST_DB_USER", "pulseops_app"));
        registry.add("spring.datasource.password", () -> System.getenv("PULSEOPS_TEST_DB_PASSWORD"));
        registry.add("pulseops.security.jwt.secret", () -> TestProperties.TEST_JWT_SECRET);
        registry.add("pulseops.auth.require-email-verification", () -> "false");
        registry.add("pulseops.mail.dev-log-only", () -> "true");
    }

    @AfterEach
    void clear() {
        TenantContext.clear();
    }

    @Test
    void customerCanOnboardAServiceAndSendTelemetry() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);

        // 1. Register + login (verification disabled for this test; covered by unit tests).
        Session alice = registerAndLogin("alice-" + suffix + "@it.test");

        // 2. Organization, project, service.
        String orgId = field(call(alice, post("/api/v1/organizations"),
                "{\"name\":\"Acme " + suffix + "\"}", 201), "id");
        String projectId = field(call(alice, post("/api/v1/organizations/" + orgId + "/projects"),
                "{\"name\":\"Shop\",\"environment\":\"production\",\"techStack\":\"Spring Boot\"}", 201), "id");
        JsonNode service = body(call(alice, post("/api/v1/organizations/" + orgId + "/projects/" + projectId
                + "/services"), "{\"name\":\"Order Service\",\"technology\":\"java_spring_boot\"}", 201));
        String serviceId = service.get("id").asText();
        assertThat(service.at("/connection/status").asText()).isEqualTo("NOT_CONNECTED");

        // 3. Onboarding: choose REST, issue a key. Still NOT connected — no data yet.
        String integrationPath = "/api/v1/organizations/" + orgId + "/services/" + serviceId + "/integration";
        JsonNode configured = body(call(alice, put(integrationPath), "{\"type\":\"REST_API\"}", 200));
        assertThat(configured.get("status").asText()).isEqualTo("WAITING_FOR_DATA");
        JsonNode issued = body(call(alice, post("/api/v1/organizations/" + orgId + "/projects/" + projectId
                + "/credentials"), "{\"name\":\"IT key\"}", 201));
        String key = issued.get("key").asText();
        String credentialId = issued.at("/credential/id").asText();
        assertThat(key).startsWith("po_");

        // 4. Send real telemetry → 202.
        String now = Instant.now().truncatedTo(ChronoUnit.SECONDS).toString();
        String batch = """
                {"service":"Order Service","events":[
                  {"type":"performance","name":"GET /orders/{id}","timestamp":"%1$s","httpMethod":"GET",
                   "path":"/orders/12345","statusCode":200,"durationMs":42.5,
                   "attributes":{"password":"hunter2","region":"ap-south-1"}},
                  {"type":"error","name":"PaymentDeclined","timestamp":"%1$s","message":"card 4111 1111 1111 1111 declined"}
                ]}""".formatted(now);
        mvc.perform(post("/api/v1/ingest/v1/events").header("X-PulseOps-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isAccepted())
                .andExpect(jsonPath("$.accepted").value(2));

        // 5. The async processor stores it and the service becomes CONNECTED.
        String connection = null;
        for (int i = 0; i < 50 && !"CONNECTED".equals(connection); i++) {
            Thread.sleep(200);
            connection = body(call(alice, get(integrationPath), null, 200)).get("status").asText();
        }
        assertThat(connection).isEqualTo("CONNECTED");

        // 6. Data landed — read under the org's tenant context (RLS), redacted and templated.
        UUID org = UUID.fromString(orgId);
        UUID svc = UUID.fromString(serviceId);
        TenantContext.callInOrganization(org, () -> {
            assertThat(jdbc.queryForObject("SELECT route_template FROM api_metrics WHERE service_id = ?",
                    String.class, svc)).isEqualTo("/orders/{id}");
            assertThat(jdbc.queryForObject(
                    "SELECT attributes->>'password' FROM telemetry_events WHERE service_id = ? AND event_type = 'performance'",
                    String.class, svc)).isEqualTo("[REDACTED]");
            assertThat(jdbc.queryForObject("SELECT message FROM application_logs WHERE service_id = ?",
                    String.class, svc)).isEqualTo("PaymentDeclined: card [REDACTED] declined");
            return null;
        });
        assertThat(jdbc.queryForObject("SELECT count(*) FROM api_metrics WHERE service_id = ?", Long.class, svc))
                .as("without a tenant context RLS hides everything").isZero();

        // 7. Another customer cannot see Alice's organization (404, not 403).
        Session mallory = registerAndLogin("mallory-" + suffix + "@it.test");
        call(mallory, get("/api/v1/organizations/" + orgId), null, 404);
        call(mallory, get("/api/v1/organizations/" + orgId + "/projects"), null, 404);

        // 8. Revoked keys stop working immediately.
        call(alice, post("/api/v1/organizations/" + orgId + "/credentials/" + credentialId + "/revoke"), null, 200);
        mvc.perform(post("/api/v1/ingest/v1/events").header("X-PulseOps-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("CREDENTIAL_REVOKED"));

        // 9. User JWTs are not accepted on the ingestion path.
        mvc.perform(post("/api/v1/ingest/v1/events").header("Authorization", "Bearer " + alice.accessToken())
                        .contentType(MediaType.APPLICATION_JSON).content(batch))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("INVALID_CREDENTIAL"));
    }

    @Test
    void openTelemetryJavaAgentExportsBecomeTracesLogsDbCallsAndMetrics() throws Exception {
        String suffix = UUID.randomUUID().toString().substring(0, 8);
        Session carol = registerAndLogin("carol-" + suffix + "@it.test");
        String orgId = field(call(carol, post("/api/v1/organizations"),
                "{\"name\":\"Otel " + suffix + "\"}", 201), "id");
        String projectId = field(call(carol, post("/api/v1/organizations/" + orgId + "/projects"),
                "{\"name\":\"Shop\",\"environment\":\"production\"}", 201), "id");
        String serviceId = field(call(carol, post("/api/v1/organizations/" + orgId + "/projects/" + projectId
                + "/services"), "{\"name\":\"checkout\",\"technology\":\"java_spring_boot\"}", 201), "id");
        String integrationPath = "/api/v1/organizations/" + orgId + "/services/" + serviceId + "/integration";
        call(carol, put(integrationPath), "{\"type\":\"JAVA_AGENT\"}", 200);
        JsonNode guide = body(call(carol, get(integrationPath + "/instructions?type=JAVA_AGENT"), null, 200));
        assertThat(guide.get("available").asBoolean()).isTrue();
        String key = body(call(carol, post("/api/v1/organizations/" + orgId + "/projects/" + projectId
                + "/credentials"), "{\"name\":\"agent key\"}", 201)).get("key").asText();

        // 1. Traces (gzip, as configured by the guide). One resource names a service that does not exist.
        Instant start = Instant.now().minusSeconds(30).truncatedTo(ChronoUnit.MILLIS);
        byte[] traces = OtlpTestData.traces(
                OtlpTestData.orderRequest(OtlpTestData.javaAgentResource("checkout"), start),
                OtlpTestData.orderRequest(OtlpTestData.javaAgentResource("ghost"), start)).toByteArray();
        MvcResult exported = mvc.perform(post("/otlp/v1/traces").header("X-PulseOps-Key", key)
                        .header("Content-Encoding", "gzip")
                        .contentType("application/x-protobuf").content(OtlpTestData.gzip(traces)))
                .andExpect(status().isOk())
                .andReturn();
        ExportTraceServiceResponse traceResponse =
                ExportTraceServiceResponse.parseFrom(exported.getResponse().getContentAsByteArray());
        assertThat(traceResponse.getPartialSuccess().getRejectedSpans()).isEqualTo(2);
        assertThat(traceResponse.getPartialSuccess().getErrorMessage()).contains("ghost");

        // 2. Logs and metrics.
        mvc.perform(post("/otlp/v1/logs").header("X-PulseOps-Key", key).contentType("application/x-protobuf")
                        .content(OtlpTestData.logs(OtlpTestData.javaAgentResource("checkout"), start).toByteArray()))
                .andExpect(status().isOk());
        Instant minute = start.truncatedTo(ChronoUnit.MINUTES);
        mvc.perform(post("/otlp/v1/metrics").header("X-PulseOps-Key", key).contentType("application/x-protobuf")
                        .content(OtlpTestData.metrics(OtlpTestData.javaAgentResource("checkout"), minute)
                                .toByteArray()))
                .andExpect(status().isOk());

        // 3. Wire-format errors are protobuf google.rpc.Status bodies; JSON is refused.
        mvc.perform(post("/otlp/v1/traces").header("X-PulseOps-Key", key)
                        .contentType(MediaType.APPLICATION_JSON).content("{}"))
                .andExpect(status().isUnsupportedMediaType());
        mvc.perform(post("/otlp/v1/traces").header("X-PulseOps-Key", "po_" + "A".repeat(43))
                        .contentType("application/x-protobuf").content(traces))
                .andExpect(status().isUnauthorized())
                .andExpect(result -> assertThat(result.getResponse().getContentType())
                        .startsWith("application/x-protobuf"));

        // 4. The agent's data is processed; the service becomes CONNECTED.
        String connection = null;
        for (int i = 0; i < 50 && !"CONNECTED".equals(connection); i++) {
            Thread.sleep(200);
            connection = body(call(carol, get(integrationPath), null, 200)).get("status").asText();
        }
        assertThat(connection).isEqualTo("CONNECTED");

        // 5. A retried export (same span IDs) does not duplicate spans.
        mvc.perform(post("/otlp/v1/traces").header("X-PulseOps-Key", key).contentType("application/x-protobuf")
                        .content(OtlpTestData.traces(OtlpTestData.orderRequest(
                                OtlpTestData.javaAgentResource("checkout"), start)).toByteArray()))
                .andExpect(status().isOk());
        Thread.sleep(1000);

        UUID org = UUID.fromString(orgId);
        UUID svc = UUID.fromString(serviceId);
        TenantContext.callInOrganization(org, () -> {
            assertThat(jdbc.queryForObject("SELECT count(*) FROM distributed_traces WHERE service_id = ?",
                    Long.class, svc)).as("2 spans, retry deduplicated").isEqualTo(2L);
            assertThat(jdbc.queryForObject("SELECT attributes->>'http.request.header.authorization' "
                    + "FROM distributed_traces WHERE service_id = ? AND kind = 'SERVER'", String.class, svc))
                    .isEqualTo("[REDACTED]");
            assertThat(jdbc.queryForObject("SELECT operation FROM database_operations WHERE service_id = ? LIMIT 1",
                    String.class, svc)).isEqualTo("SELECT * FROM orders WHERE id = ?");
            assertThat(jdbc.queryForObject("SELECT sum(error_count) FROM api_metrics WHERE service_id = ? "
                    + "AND route_template = '/orders/{id}'", Long.class, svc)).isPositive();
            assertThat(jdbc.queryForObject("SELECT count(*) FROM application_logs WHERE service_id = ? "
                    + "AND message LIKE 'java.sql.SQLTransientConnectionException%'", Long.class, svc)).isPositive();
            assertThat(jdbc.queryForObject("SELECT message FROM application_logs WHERE service_id = ? AND level = 'WARN'",
                    String.class, svc)).isEqualTo("Payment retry for card [REDACTED]");
            assertThat(jdbc.queryForObject("SELECT sample_count FROM metric_points WHERE service_id = ? "
                    + "AND name = 'jvm.memory.used'", Long.class, svc)).isEqualTo(2L);
            return null;
        });

        // 6. Monitoring APIs (Phase 9) read the same data back, percentiles from histograms.
        String monitoring = "/api/v1/organizations/" + orgId + "/services/" + serviceId + "/monitoring";
        JsonNode summary = body(call(carol, get(monitoring + "/summary"), null, 200));
        for (int i = 0; i < 25 && summary.get("requests").asLong() < 2; i++) {   // retried export may lag
            Thread.sleep(200);
            summary = body(call(carol, get(monitoring + "/summary"), null, 200));
        }
        assertThat(summary.get("requests").asLong()).as("original export + retry").isEqualTo(2);
        assertThat(summary.get("serverErrors").asLong()).isEqualTo(2);
        assertThat(summary.get("errorRate").asDouble()).isEqualTo(1.0);
        assertThat(summary.get("p95Ms").asDouble()).as("capped at the observed max").isEqualTo(120.0);
        assertThat(summary.get("statusClasses").get("5xx").asLong()).isEqualTo(2);
        assertThat(summary.get("connectionStatus").asText()).isEqualTo("CONNECTED");
        assertThat(summary.get("health").get("status").asText()).as("2 requests: too few to judge")
                .isEqualTo("HEALTHY");

        JsonNode endpoints = body(call(carol, get(monitoring + "/endpoints?sort=p95"), null, 200));
        assertThat(endpoints.get("endpoints")).hasSize(1);
        JsonNode endpoint = endpoints.get("endpoints").get(0);
        assertThat(endpoint.get("method").asText()).isEqualTo("GET");
        assertThat(endpoint.get("route").asText()).isEqualTo("/orders/{id}");
        assertThat(endpoint.get("avgMs").asDouble()).isEqualTo(120.0);

        JsonNode series = body(call(carol, get(monitoring + "/timeseries?method=GET&route={route}", "/orders/{id}"),
                null, 200));
        long seriesRequests = 0;
        for (JsonNode point : series.get("points")) {
            seriesRequests += point.get("requests").asLong();
        }
        assertThat(series.get("points").size()).as("last hour, minute steps").isBetween(60, 61);
        assertThat(seriesRequests).isEqualTo(2);

        JsonNode runtime = body(call(carol, get(monitoring + "/runtime"), null, 200));
        assertThat(runtime.get("available").asBoolean()).isTrue();
        boolean heapSeen = false;
        for (JsonNode point : runtime.get("points")) {
            heapSeen |= point.get("heapUsedBytes").asDouble() == 200.0;
        }
        assertThat(heapSeen).as("average of the two heap gauge samples").isTrue();

        JsonNode overview = body(call(carol, get("/api/v1/organizations/" + orgId + "/monitoring/overview"),
                null, 200));
        assertThat(overview.get("services").asInt()).isEqualTo(1);
        assertThat(overview.get("connectedServices").asInt()).isEqualTo(1);
        assertThat(overview.get("requests").asLong()).isEqualTo(2);

        assertThat(overview.get("database").get("calls").asLong()).isEqualTo(2);

        // 7. Database monitoring (Phase 10): exact percentiles over the derived DB calls.
        JsonNode db = body(call(carol, get(monitoring + "/database/summary"), null, 200));
        assertThat(db.get("calls").asLong()).as("one DB span per export (original + retry)").isEqualTo(2);
        assertThat(db.get("p95Ms").asDouble()).isEqualTo(100.0);
        assertThat(db.get("slowCalls").asLong()).as("default threshold 500 ms").isZero();
        assertThat(db.get("breakdown").get(0).get("dbSystem").asText()).isEqualTo("postgresql");
        assertThat(body(call(carol, get(monitoring + "/database/summary?slowMs=50"), null, 200))
                .get("slowCalls").asLong()).isEqualTo(2);

        JsonNode ops = body(call(carol, get(monitoring + "/database/operations?sort=p95"), null, 200));
        assertThat(ops.get("operations")).hasSize(1);
        JsonNode op = ops.get("operations").get(0);
        assertThat(op.get("operation").asText()).isEqualTo("SELECT * FROM orders WHERE id = ?");
        assertThat(op.get("table").asText()).isEqualTo("orders");
        assertThat(op.get("shareOfTime").asDouble()).isEqualTo(1.0);
        assertThat(op.get("slowestTraceId").asText()).isEqualTo(OtlpTestData.TRACE_ID);

        JsonNode calls = body(call(carol, get(monitoring + "/database/calls?minDurationMs=50"), null, 200));
        assertThat(calls.get("calls")).hasSize(2);
        assertThat(calls.get("calls").get(0).get("traceId").asText()).isEqualTo(OtlpTestData.TRACE_ID);
        JsonNode dbSeries = body(call(carol, get(monitoring + "/database/timeseries"), null, 200));
        long seriesCalls = 0;
        for (JsonNode point : dbSeries.get("points")) {
            seriesCalls += point.get("calls").asLong();
        }
        assertThat(seriesCalls).isEqualTo(2);
        assertThat(body(call(carol, get(monitoring + "/database/pool"), null, 200)).get("available").asBoolean())
                .as("the test export has no pool metrics").isFalse();
        call(carol, get(monitoring + "/database/operations?sort=fastest"), null, 400);

        // 8. Logs and traces explorer (Phase 11).
        String orgApi = "/api/v1/organizations/" + orgId;
        JsonNode tracePage = body(call(carol, get(orgApi + "/traces?serviceId=" + serviceId), null, 200));
        assertThat(tracePage.get("traces")).hasSize(1);
        JsonNode traceSummary = tracePage.get("traces").get(0);
        assertThat(traceSummary.get("traceId").asText()).isEqualTo(OtlpTestData.TRACE_ID);
        assertThat(traceSummary.get("rootName").asText()).isEqualTo("GET /orders/{id}");
        assertThat(traceSummary.get("spans").asLong()).isEqualTo(2);
        assertThat(traceSummary.get("errorSpans").asLong()).isEqualTo(1);
        assertThat(body(call(carol, get(orgApi + "/traces?minDurationMs=500"), null, 200)).get("traces")).isEmpty();

        JsonNode trace = body(call(carol, get(orgApi + "/traces/" + OtlpTestData.TRACE_ID), null, 200));
        assertThat(trace.get("complete").asBoolean()).isTrue();
        assertThat(trace.get("spans").get(0).get("spanId").asText()).isEqualTo(OtlpTestData.SERVER_SPAN_ID);
        assertThat(trace.get("spans").get(1).get("depth").asInt()).isEqualTo(1);
        assertThat(trace.get("spans").get(1).get("offsetMs").asDouble()).isEqualTo(5.0);
        assertThat(trace.get("logs").size()).as("exception log(s) + the WARN log of the trace").isGreaterThanOrEqualTo(2);

        JsonNode warn = body(call(carol, get(orgApi + "/logs?minLevel=WARN&q=payment"), null, 200));
        assertThat(warn.get("logs")).hasSize(1);
        assertThat(warn.get("logs").get(0).get("message").asText()).isEqualTo("Payment retry for card [REDACTED]");
        assertThat(warn.get("logs").get(0).get("serviceName").asText()).isEqualTo("checkout");
        JsonNode page1 = body(call(carol, get(orgApi + "/logs?limit=1"), null, 200));
        assertThat(page1.get("nextCursor").isNull()).isFalse();
        JsonNode page2 = body(call(carol, get(orgApi + "/logs?limit=1&cursor=" + page1.get("nextCursor").asText()),
                null, 200));
        assertThat(page2.get("logs").get(0).get("id").asText()).isNotEqualTo(page1.get("logs").get(0).get("id").asText());
        JsonNode histogram = body(call(carol, get(orgApi + "/logs/histogram"), null, 200));
        long histogramTotal = 0;
        for (JsonNode b : histogram.get("buckets")) {
            histogramTotal += b.get("total").asLong();
        }
        assertThat(histogramTotal).isGreaterThanOrEqualTo(2);
        call(carol, get(orgApi + "/traces/not-a-trace"), null, 400);
        call(carol, get(orgApi + "/traces/" + "0".repeat(31) + "1"), null, 404);
        call(carol, get(orgApi + "/logs?cursor=garbage"), null, 400);

        call(carol, get(monitoring + "/summary?from=not-a-time"), null, 400);
        Session mallory = registerAndLogin("mallory-" + suffix + "@it.test");
        call(mallory, get(monitoring + "/summary"), null, 404);
        call(mallory, get("/api/v1/organizations/" + orgId + "/traces/" + OtlpTestData.TRACE_ID), null, 404);
    }

    @Test
    void refreshTokensRotateAndReuseRevokesTheSession() throws Exception {
        Session bob = registerAndLogin("bob-" + UUID.randomUUID().toString().substring(0, 8) + "@it.test");

        MvcResult rotated = mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("pulseops_refresh", bob.refreshToken()))
                        .header("X-PulseOps-Client", "web"))
                .andExpect(status().isOk())
                .andReturn();
        String newRefresh = refreshCookie(rotated);
        assertThat(newRefresh).isNotEqualTo(bob.refreshToken());

        // Replaying the old (rotated) token = theft signal → every session revoked.
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("pulseops_refresh", bob.refreshToken()))
                        .header("X-PulseOps-Client", "web"))
                .andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("SESSION_REVOKED"));
        mvc.perform(post("/api/v1/auth/refresh")
                        .cookie(new Cookie("pulseops_refresh", newRefresh))
                        .header("X-PulseOps-Client", "web"))
                .andExpect(status().isUnauthorized());

        // Missing CSRF header is refused even with a valid-looking cookie.
        mvc.perform(post("/api/v1/auth/refresh").cookie(new Cookie("pulseops_refresh", newRefresh)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("CSRF_CHECK_FAILED"));
    }

    @Test
    void protectedEndpointsRequireAuthentication() throws Exception {
        mvc.perform(get("/api/v1/organizations")).andExpect(status().isUnauthorized())
                .andExpect(jsonPath("$.code").value("UNAUTHENTICATED"));
        mvc.perform(get("/api/v1/users/me").header("Authorization", "Bearer not-a-jwt"))
                .andExpect(status().isUnauthorized());
        mvc.perform(get("/actuator/health")).andExpect(status().isOk());
        mvc.perform(get("/actuator/env")).andExpect(status().is4xxClientError());
    }

    // ------------------------------------------------------------------ helpers

    private record Session(String accessToken, String refreshToken) {
    }

    private Session registerAndLogin(String email) throws Exception {
        mvc.perform(post("/api/v1/auth/register").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD
                                + "\",\"fullName\":\"IT User\"}"))
                .andExpect(status().isCreated());
        MvcResult login = mvc.perform(post("/api/v1/auth/login").contentType(MediaType.APPLICATION_JSON)
                        .content("{\"email\":\"" + email + "\",\"password\":\"" + PASSWORD + "\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.tokenType").value("Bearer"))
                .andReturn();
        return new Session(body(login).get("accessToken").asText(), refreshCookie(login));
    }

    private MvcResult call(Session session, MockHttpServletRequestBuilder request, String jsonBody,
                           int expectedStatus) throws Exception {
        request.header("Authorization", "Bearer " + session.accessToken());
        if (jsonBody != null) {
            request.contentType(MediaType.APPLICATION_JSON).content(jsonBody);
        }
        return mvc.perform(request).andExpect(status().is(expectedStatus)).andReturn();
    }

    private JsonNode body(MvcResult result) throws Exception {
        return json.readTree(result.getResponse().getContentAsString());
    }

    private String field(MvcResult result, String name) throws Exception {
        return body(result).get(name).asText();
    }

    private static String refreshCookie(MvcResult result) {
        for (String header : result.getResponse().getHeaders("Set-Cookie")) {
            Matcher m = REFRESH_COOKIE.matcher(header);
            if (m.find() && !m.group(1).isEmpty()) {
                return m.group(1);
            }
        }
        throw new AssertionError("No refresh cookie in response");
    }
}
