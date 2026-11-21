package com.pulseops;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.boot.context.properties.ConfigurationPropertiesScan;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.annotation.EnableScheduling;

/**
 * PulseOps backend — modular monolith.
 *
 * <p>Module packages live under {@code com.pulseops.<module>}; see
 * docs/ARCHITECTURE.md for the module map and boundary rules.</p>
 */
@SpringBootApplication
@ConfigurationPropertiesScan
@EnableScheduling
@EnableAsync
public class PulseOpsApplication {

    public static void main(String[] args) {
        SpringApplication.run(PulseOpsApplication.class, args);
    }
}
