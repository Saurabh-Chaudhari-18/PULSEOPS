package com.pulseops.sample.orders;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Map;

import com.pulseops.sample.orders.chaos.ChaosSettings;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;

/** Runs the real app on a random port (traffic generator off) and exercises every behaviour. */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"sample.traffic.requests-per-second=0", "server.address=127.0.0.1"})
class OrderServiceApplicationTest {

    @Autowired
    TestRestTemplate rest;
    @Autowired
    ChaosSettings chaos;

    @AfterEach
    void resetChaos() {
        chaos.set(ChaosSettings.preset("normal"));
    }

    private ResponseEntity<Map> order(String sku, int quantity) {
        return rest.postForEntity("/api/orders",
                Map.of("customerEmail", "shopper@example.com", "sku", sku, "quantity", quantity), Map.class);
    }

    @Test
    void readsSeededOrdersAndReports404ForMissingOnes() {
        assertThat(rest.getForEntity("/api/orders/1", Map.class).getStatusCode()).isEqualTo(HttpStatus.OK);
        ResponseEntity<Map> missing = rest.getForEntity("/api/orders/999999", Map.class);
        assertThat(missing.getStatusCode()).isEqualTo(HttpStatus.NOT_FOUND);
        assertThat(missing.getBody()).containsEntry("code", "ORDER_NOT_FOUND");
        assertThat(rest.getForEntity("/api/orders?status=PLACED&limit=5", Object[].class).getBody()).hasSizeLessThanOrEqualTo(5);
    }

    @Test
    void placesAnOrderThroughTheInventoryApiAndPayment() {
        ResponseEntity<Map> created = order("MN-303", 2);
        assertThat(created.getStatusCode()).isEqualTo(HttpStatus.CREATED);
        assertThat(created.getBody()).containsEntry("amountCents", 51_998);
        assertThat(created.getHeaders().getLocation()).isNotNull();
    }

    @Test
    void businessFailuresHaveDistinctStatuses() {
        assertThat(order("LP-404", 1).getStatusCode()).isEqualTo(HttpStatus.PAYMENT_REQUIRED);
        assertThat(order("XX-999", 1).getStatusCode()).isEqualTo(HttpStatus.UNPROCESSABLE_ENTITY);
        assertThat(order("not-a-sku", 1).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }

    @Test
    void inventoryFaultsSurfaceAs503() {
        chaos.set(new ChaosSettings.Snapshot(0, 1.0, 0));
        ResponseEntity<Map> failed = order("KB-101", 1);
        assertThat(failed.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(failed.getBody()).containsEntry("code", "INVENTORY_UNAVAILABLE");
    }

    @Test
    void slowDatabaseFaultSlowsTheQuery() {
        chaos.set(new ChaosSettings.Snapshot(0, 0, 300));
        long start = System.nanoTime();
        assertThat(rest.getForEntity("/api/orders/report", Object[].class).getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat((System.nanoTime() - start) / 1_000_000).isGreaterThanOrEqualTo(300);
    }

    @Test
    void chaosEndpointAcceptsPresetsAndRejectsNonsense() {
        rest.put("/admin/chaos?preset=slow-db", null);
        assertThat(chaos.get().slowDbMs()).isEqualTo(1_500);
        assertThat(rest.exchange("/admin/chaos?preset=meteor", org.springframework.http.HttpMethod.PUT, null,
                String.class).getStatusCode()).isEqualTo(HttpStatus.BAD_REQUEST);
    }
}
