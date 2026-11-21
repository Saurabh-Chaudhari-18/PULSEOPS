package com.pulseops.sample.orders;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * A small but realistic order service used to validate the PulseOps customer journey:
 * HTTP endpoints, JPA/JDBC queries, an HTTP call to a downstream (inventory) API, business
 * errors, logs and injectable faults. It contains no observability code at all.
 */
@SpringBootApplication
@EnableScheduling
public class OrderServiceApplication {

    public static void main(String[] args) {
        SpringApplication.run(OrderServiceApplication.class, args);
    }
}
