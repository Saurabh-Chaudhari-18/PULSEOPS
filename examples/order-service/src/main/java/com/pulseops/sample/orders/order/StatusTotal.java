package com.pulseops.sample.orders.order;

/** One row of the sales report. */
public record StatusTotal(OrderStatus status, Long orders, Long amountCents) {
}
