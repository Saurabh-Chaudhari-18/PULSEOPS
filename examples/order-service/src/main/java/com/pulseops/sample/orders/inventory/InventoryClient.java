package com.pulseops.sample.orders.inventory;

import java.time.Duration;

import org.springframework.core.env.Environment;
import org.springframework.http.client.JdkClientHttpRequestFactory;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/** HTTP client for the inventory API (java.net.http underneath, which the agent instruments). */
@Component
public class InventoryClient {

    public static class UnknownSkuException extends RuntimeException {
        public UnknownSkuException(String sku) {
            super("Unknown SKU " + sku);
        }
    }

    public static class InventoryUnavailableException extends RuntimeException {
        public InventoryUnavailableException(String message, Throwable cause) {
            super(message, cause);
        }
    }

    private final RestClient http;
    private final Environment environment;

    public InventoryClient(RestClient.Builder builder, Environment environment) {
        JdkClientHttpRequestFactory factory = new JdkClientHttpRequestFactory();
        factory.setReadTimeout(Duration.ofSeconds(5));
        this.http = builder.requestFactory(factory).build();
        this.environment = environment;
    }

    public InventoryController.StockView stock(String sku) {
        try {
            return http.get().uri(baseUrl() + "/internal/inventory/{sku}", sku)
                    .retrieve()
                    .body(InventoryController.StockView.class);
        } catch (HttpClientErrorException.NotFound e) {
            throw new UnknownSkuException(sku);
        } catch (RestClientException e) {
            throw new InventoryUnavailableException("Inventory service call failed: " + e.getMessage(), e);
        }
    }

    /** The inventory API is this same process; resolved per call so random test ports work. */
    private String baseUrl() {
        String port = environment.getProperty("local.server.port", environment.getProperty("server.port", "8081"));
        return "http://127.0.0.1:" + port;
    }
}
