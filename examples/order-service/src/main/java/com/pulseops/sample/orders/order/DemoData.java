package com.pulseops.sample.orders.order;

import java.time.Duration;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.stereotype.Component;

/** Seeds 50 orders (IDs 1-50) so reads have something to find. */
@Component
public class DemoData implements ApplicationRunner {

    static final int SEEDED_ORDERS = 50;
    private static final String[] SKUS = {"KB-101", "MS-202", "MN-303"};
    private static final long[] PRICES = {4_999, 2_499, 25_999};

    private final OrderRepository orders;

    public DemoData(OrderRepository orders) {
        this.orders = orders;
    }

    @Override
    public void run(ApplicationArguments args) {
        if (orders.count() > 0) {
            return;
        }
        Instant now = Instant.now();
        OrderStatus[] statuses = OrderStatus.values();
        List<CustomerOrder> seed = new ArrayList<>();
        for (int i = 0; i < SEEDED_ORDERS; i++) {
            int item = i % SKUS.length;
            int qty = 1 + i % 3;
            seed.add(new CustomerOrder("customer" + (i % 7) + "@example.com", SKUS[item], qty, PRICES[item] * qty,
                    statuses[i % statuses.length], now.minus(Duration.ofHours(i))));
        }
        orders.saveAll(seed);
    }
}
