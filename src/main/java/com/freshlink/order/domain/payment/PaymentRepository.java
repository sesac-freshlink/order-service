package com.freshlink.order.domain.payment;

import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** order_id는 유니크라 최대 1건이다 */
    Optional<Payment> findByOrderId(Long orderId);

    /** payment_key는 유니크다 (uk_payment_key). 다른 주문이 이미 쓴 키인지 본다 */
    boolean existsByPaymentKey(String paymentKey);
}
