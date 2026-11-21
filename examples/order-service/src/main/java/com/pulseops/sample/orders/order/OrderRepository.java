package com.pulseops.sample.orders.order;

import java.util.List;

import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

public interface OrderRepository extends JpaRepository<CustomerOrder, Long> {

    List<CustomerOrder> findByStatusOrderByCreatedAtDesc(OrderStatus status, Pageable page);

    List<CustomerOrder> findAllByOrderByCreatedAtDesc(Pageable page);

    @Query("""
            select new com.pulseops.sample.orders.order.StatusTotal(o.status, count(o), coalesce(sum(o.amountCents), 0L))
            from CustomerOrder o group by o.status order by o.status
            """)
    List<StatusTotal> totalsByStatus();
}
