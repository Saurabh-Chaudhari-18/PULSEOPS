package com.pulseops.sample.orders.order;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import com.pulseops.sample.orders.chaos.ChaosSettings;

import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/orders")
public class OrderController {

    private final OrderService orders;
    private final ChaosSettings chaos;

    public OrderController(OrderService orders, ChaosSettings chaos) {
        this.orders = orders;
        this.chaos = chaos;
    }

    @GetMapping("/{id}")
    public ResponseEntity<OrderDtos.OrderView> get(@PathVariable long id) {
        chaos.maybeDelay();
        return orders.find(id)
                .map(o -> ResponseEntity.ok(OrderDtos.OrderView.from(o)))
                .orElseThrow(() -> new OrderNotFoundException(id));
    }

    @GetMapping
    public List<OrderDtos.OrderView> list(@RequestParam(required = false) OrderStatus status,
                                          @RequestParam(defaultValue = "20") int limit) {
        chaos.maybeDelay();
        return orders.recent(status, limit).stream().map(OrderDtos.OrderView::from).toList();
    }

    @PostMapping
    public ResponseEntity<OrderDtos.OrderView> create(@Valid @RequestBody OrderDtos.CreateOrderRequest request) {
        chaos.maybeDelay();
        CustomerOrder order = orders.place(request);
        return ResponseEntity.created(URI.create("/api/orders/" + order.getId())).body(OrderDtos.OrderView.from(order));
    }

    @GetMapping("/report")
    public List<StatusTotal> report() {
        chaos.maybeDelay();
        return orders.salesReport();
    }

    public static class OrderNotFoundException extends RuntimeException {
        public OrderNotFoundException(long id) {
            super("Order " + id + " not found");
        }
    }
}
