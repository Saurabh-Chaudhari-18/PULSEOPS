package com.pulseops.sample.orders.order;

import java.time.Clock;
import java.util.List;
import java.util.Optional;

import com.pulseops.sample.orders.chaos.DbDelay;
import com.pulseops.sample.orders.inventory.InventoryClient;
import com.pulseops.sample.orders.inventory.InventoryController;
import com.pulseops.sample.orders.payment.PaymentGateway;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class OrderService {

    private static final Logger log = LoggerFactory.getLogger(OrderService.class);

    private final OrderRepository orders;
    private final InventoryClient inventory;
    private final PaymentGateway payments;
    private final DbDelay dbDelay;
    private final Clock clock;

    public OrderService(OrderRepository orders, InventoryClient inventory, PaymentGateway payments, DbDelay dbDelay) {
        this.orders = orders;
        this.inventory = inventory;
        this.payments = payments;
        this.dbDelay = dbDelay;
        this.clock = Clock.systemUTC();
    }

    @Transactional(readOnly = true)
    public Optional<CustomerOrder> find(long id) {
        dbDelay.apply();
        return orders.findById(id);
    }

    @Transactional(readOnly = true)
    public List<CustomerOrder> recent(OrderStatus status, int limit) {
        PageRequest page = PageRequest.of(0, Math.min(Math.max(limit, 1), 100));
        return status == null ? orders.findAllByOrderByCreatedAtDesc(page)
                : orders.findByStatusOrderByCreatedAtDesc(status, page);
    }

    /** Inventory (HTTP) → payment → insert. Not one transaction: the remote call stays outside it. */
    public CustomerOrder place(OrderDtos.CreateOrderRequest request) {
        InventoryController.StockView stock = inventory.stock(request.sku());
        long amount = stock.unitPriceCents() * request.quantity();
        payments.charge(request.customerEmail(), amount);
        CustomerOrder saved = orders.save(new CustomerOrder(request.customerEmail(), request.sku(),
                request.quantity(), amount, OrderStatus.PLACED, clock.instant()));
        log.info("Order {} placed: {} x {} for {} cents", saved.getId(), request.quantity(), request.sku(), amount);
        return saved;
    }

    @Transactional(readOnly = true)
    public List<StatusTotal> salesReport() {
        dbDelay.apply();
        return orders.totalsByStatus();
    }
}
