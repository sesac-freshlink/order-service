package com.freshlink.order.domain.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** order_id는 유니크라 최대 1건이다 */
    Optional<Payment> findByOrderId(Long orderId);
}
