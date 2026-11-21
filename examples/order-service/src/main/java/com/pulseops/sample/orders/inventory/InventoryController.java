package com.pulseops.sample.orders.inventory;

import java.util.Map;

import com.pulseops.sample.orders.chaos.ChaosSettings;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

/**
 * Stand-in for a separate inventory microservice. It lives in the same process to keep the
 * sample to one JVM, but is called over real HTTP, so traces show a CLIENT span in the order
 * flow with the inventory SERVER span as its child.
 */
@RestController
public class InventoryController {

    private static final Logger log = LoggerFactory.getLogger(InventoryController.class);

    static final Map<String, Long> PRICES_CENTS = Map.of(
            "KB-101", 4_999L,     // keyboard
            "MS-202", 2_499L,     // mouse
            "MN-303", 25_999L,    // monitor
            "LP-404", 129_999L);  // laptop: above the payment limit, always declined

    private final ChaosSettings chaos;

    public InventoryController(ChaosSettings chaos) {
        this.chaos = chaos;
    }

    public record StockView(String sku, long unitPriceCents, boolean available) {
    }

    @GetMapping("/internal/inventory/{sku}")
    public StockView stock(@PathVariable String sku) {
        chaos.maybeDelay();
        if (chaos.shouldFail()) {
            IllegalStateException failure = new IllegalStateException(
                    "Inventory store timed out while reading stock for " + sku);
            log.error("Inventory lookup failed", failure);
            throw failure;
        }
        Long price = PRICES_CENTS.get(sku);
        if (price == null) {
            throw new ResponseStatusException(HttpStatus.NOT_FOUND, "Unknown SKU " + sku);
        }
        return new StockView(sku, price, true);
    }
}
