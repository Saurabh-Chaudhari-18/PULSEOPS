package com.pulseops.sample.orders.order;

import com.pulseops.sample.orders.inventory.InventoryClient;
import com.pulseops.sample.orders.payment.PaymentGateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** Maps business failures to HTTP statuses and logs them at the level an operator would expect. */
@RestControllerAdvice(basePackages = "com.pulseops.sample.orders.order")
public class ApiErrors {

    private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

    @ExceptionHandler(OrderController.OrderNotFoundException.class)
    ResponseEntity<OrderDtos.ErrorView> notFound(OrderController.OrderNotFoundException e) {
        return error(HttpStatus.NOT_FOUND, "ORDER_NOT_FOUND", e.getMessage());
    }

    @ExceptionHandler(InventoryClient.UnknownSkuException.class)
    ResponseEntity<OrderDtos.ErrorView> unknownSku(InventoryClient.UnknownSkuException e) {
        return error(HttpStatus.UNPROCESSABLE_ENTITY, "UNKNOWN_SKU", e.getMessage());
    }

    @ExceptionHandler(PaymentGateway.PaymentDeclinedException.class)
    ResponseEntity<OrderDtos.ErrorView> declined(PaymentGateway.PaymentDeclinedException e) {
        log.warn("Payment declined: {}", e.getMessage());
        return error(HttpStatus.PAYMENT_REQUIRED, "PAYMENT_DECLINED", e.getMessage());
    }

    @ExceptionHandler(InventoryClient.InventoryUnavailableException.class)
    ResponseEntity<OrderDtos.ErrorView> inventoryDown(InventoryClient.InventoryUnavailableException e) {
        log.error("Could not place order: inventory unavailable", e);
        return error(HttpStatus.SERVICE_UNAVAILABLE, "INVENTORY_UNAVAILABLE", "Inventory is temporarily unavailable");
    }

    @ExceptionHandler(MethodArgumentNotValidException.class)
    ResponseEntity<OrderDtos.ErrorView> invalid(MethodArgumentNotValidException e) {
        String detail = e.getBindingResult().getFieldErrors().stream()
                .map(f -> f.getField() + " " + f.getDefaultMessage())
                .findFirst().orElse("invalid request");
        return error(HttpStatus.BAD_REQUEST, "VALIDATION_FAILED", detail);
    }

    private static ResponseEntity<OrderDtos.ErrorView> error(HttpStatus status, String code, String message) {
        return ResponseEntity.status(status).body(new OrderDtos.ErrorView(code, message));
    }
}
