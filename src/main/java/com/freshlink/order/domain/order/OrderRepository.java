package com.freshlink.order.domain.order;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** (customer_id, request_id)는 유니크 키라 최대 1건이다 */
    Optional<Order> findByCustomerIdAndRequestId(String customerId, String requestId);
}
