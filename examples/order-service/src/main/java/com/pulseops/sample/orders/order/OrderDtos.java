package com.pulseops.sample.orders.order;

import java.time.Instant;

import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;

public final class OrderDtos {

    private OrderDtos() {
    }

    public record CreateOrderRequest(
            @NotBlank @Email String customerEmail,
            @NotBlank @Pattern(regexp = "^[A-Z]{2}-\\d{3}$", message = "must look like AB-123") String sku,
            @Min(1) @Max(100) int quantity) {
    }

    public record OrderView(long id, String customerEmail, String sku, int quantity, long amountCents,
                            OrderStatus status, Instant createdAt) {

        static OrderView from(CustomerOrder o) {
            return new OrderView(o.getId(), o.getCustomerEmail(), o.getSku(), o.getQuantity(), o.getAmountCents(),
                    o.getStatus(), o.getCreatedAt());
        }
    }

    public record ErrorView(String code, String message) {
    }
}
