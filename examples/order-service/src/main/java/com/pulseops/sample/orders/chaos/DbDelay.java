package com.pulseops.sample.orders.chaos;

import org.springframework.boot.context.event.ApplicationReadyEvent;
import org.springframework.context.event.EventListener;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.stereotype.Component;

/**
 * Makes the database itself slow: an H2 function that sleeps inside the SQL call, so the
 * delay shows up on the JDBC span (not just the HTTP span), as a real slow query would.
 */
@Component
public class DbDelay {

    private final JdbcTemplate jdbc;
    private final ChaosSettings chaos;

    public DbDelay(JdbcTemplate jdbc, ChaosSettings chaos) {
        this.jdbc = jdbc;
        this.chaos = chaos;
    }

    /** Called by H2 through the PULSE_SLEEP alias. */
    public static int sleep(int ms) {
        ChaosSettings.sleep(ms);
        return 0;
    }

    @EventListener(ApplicationReadyEvent.class)
    void register() {
        jdbc.execute("CREATE ALIAS IF NOT EXISTS PULSE_SLEEP FOR 'com.pulseops.sample.orders.chaos.DbDelay.sleep'");
    }

    /** Runs a slow statement when the slow-db fault is on. */
    public void apply() {
        long ms = chaos.get().slowDbMs();
        if (ms > 0) {
            jdbc.queryForObject("SELECT PULSE_SLEEP(?)", Integer.class, (int) ms);
        }
    }
}
