package com.pulseops.sample.orders.chaos;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

/**
 * Fault injection for demos. Unauthenticated, so the service binds to 127.0.0.1 by default.
 * <pre>
 * curl -X PUT 'http://127.0.0.1:8081/admin/chaos?preset=slow-db'
 * curl -X PUT  http://127.0.0.1:8081/admin/chaos -H 'Content-Type: application/json' \
 *      -d '{"latencyMs":0,"inventoryErrorRate":0.3,"slowDbMs":0}'
 * </pre>
 */
@RestController
public class ChaosController {

    private final ChaosSettings chaos;

    public ChaosController(ChaosSettings chaos) {
        this.chaos = chaos;
    }

    @GetMapping("/admin/chaos")
    public ChaosSettings.Snapshot get() {
        return chaos.get();
    }

    @PutMapping("/admin/chaos")
    public ChaosSettings.Snapshot set(@RequestParam(required = false) String preset,
                                      @RequestBody(required = false) ChaosSettings.Snapshot body) {
        if (preset != null) {
            chaos.set(ChaosSettings.preset(preset));
        } else if (body != null) {
            chaos.set(body);
        } else {
            throw new IllegalArgumentException("Give ?preset=... or a JSON body");
        }
        return chaos.get();
    }

    @ExceptionHandler(IllegalArgumentException.class)
    ResponseEntity<String> badRequest(IllegalArgumentException e) {
        return ResponseEntity.badRequest().body(e.getMessage());
    }
}
