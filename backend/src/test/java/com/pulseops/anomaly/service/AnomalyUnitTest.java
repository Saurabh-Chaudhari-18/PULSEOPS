package com.pulseops.anomaly.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pulseops.anomaly.service.SeriesBuilder.Built;
import com.pulseops.anomaly.service.SeriesBuilder.RequestMinute;
import com.pulseops.common.error.ApiException;

import org.junit.jupiter.api.Test;

class AnomalyUnitTest {

    private static final UUID SVC = UUID.fromString("00000000-0000-0000-0000-00000000000a");
    private static final Instant T0 = Instant.parse("2026-11-23T10:00:00Z");

    @Test
    void evaluationEndsAtTheLastCompleteMinuteAfterExportLag() {
        assertThat(AnomalyDetectionJob.evaluationEnd(Instant.parse("2026-11-23T10:05:40Z")))
                .isEqualTo(Instant.parse("2026-11-23T10:05:00Z"));
        assertThat(AnomalyDetectionJob.evaluationEnd(Instant.parse("2026-11-23T10:05:10Z")))
                .as("10:04 bucket may still be arriving").isEqualTo(Instant.parse("2026-11-23T10:04:00Z"));
    }

    @Test
    void requestSeriesKeepAbsentLatencyNullAndRateZeroOnlyAfterTheFirstRequest() {
        List<Instant> minutes = SeriesBuilder.minutes(T0, T0.plusSeconds(240));
        long[] buckets = {0, 0, 10, 50};   // 60 requests up to 10 ms
        Map<Instant, RequestMinute> data = Map.of(
                T0.plusSeconds(60), new RequestMinute(60, 6, 9.0, buckets),
                T0.plusSeconds(180), new RequestMinute(60, 0, 9.0, buckets));

        List<Built> built = SeriesBuilder.requests(SVC, "service", minutes, data);

        assertThat(built).extracting(b -> b.ref().metric())
                .containsExactly("request_rate", "response_latency", "error_rate");
        assertThat(built.get(0).series().key()).isEqualTo(SVC + "|request_rate|service");
        assertThat(built.get(0).series().points()).extracting(MlClient.Point::v)
                .containsExactly(null, 1.0, 0.0, 1.0);
        MlClient.Point latency = built.get(1).series().points().get(1);
        assertThat(latency.v()).isNotNull().isLessThanOrEqualTo(9.0);
        assertThat(latency.n()).isEqualTo(60);
        assertThat(built.get(1).series().points().get(2).v()).as("no requests: no latency, not 0 ms").isNull();
        assertThat(built.get(2).series().points().get(1).v()).isEqualTo(0.1);
    }

    @Test
    void serviceWithoutRequestsProducesNoRequestSeries() {
        assertThat(SeriesBuilder.requests(SVC, "service", SeriesBuilder.minutes(T0, T0.plusSeconds(120)), Map.of()))
                .isEmpty();
    }

    @Test
    void poolUtilisationIsUsedOverMaxAndCapped() {
        List<Instant> minutes = SeriesBuilder.minutes(T0, T0.plusSeconds(180));
        List<Built> pool = SeriesBuilder.pool(SVC, minutes, Map.of(
                T0, new double[]{2, 10},
                T0.plusSeconds(60), new double[]{12, 10},
                T0.plusSeconds(120), new double[]{Double.NaN, 10}));
        assertThat(pool.get(0).series().points()).extracting(MlClient.Point::v).containsExactly(0.2, 1.0, null);
        assertThat(SeriesBuilder.pool(SVC, minutes, Map.of())).isEmpty();
    }

    @Test
    void listFiltersAreValidated() {
        assertThat(AnomalyService.statuses(null)).isEqualTo("open,linked");
        assertThat(AnomalyService.statuses("ALL")).isEqualTo("open,linked,dismissed,resolved");
        assertThatThrownBy(() -> AnomalyService.statuses("closed")).isInstanceOf(ApiException.class);
        assertThat(AnomalyService.severityRank(null)).isEqualTo(1);
        assertThat(AnomalyService.severityRank("High")).isEqualTo(3);
        assertThatThrownBy(() -> AnomalyService.severityRank("urgent")).isInstanceOf(ApiException.class);
        assertThat(AnomalyDetectionJob.truncate("x".repeat(400))).hasSize(300);
    }

    @Test
    void skipReasonsAreGroupedIntoCategories() {
        assertThat(AnomalyDetectionJob.skipCategory("insufficient history: 12 usable baseline points, 30 needed"))
                .isEqualTo("insufficient history");
        assertThat(AnomalyDetectionJob.skipCategory("only 1 usable points in the evaluation window, 3 needed"))
                .isEqualTo("only N usable points in the evaluation window, N needed");
        assertThat(AnomalyDetectionJob.skipCategory(null)).isEqualTo("unspecified");
    }
}
