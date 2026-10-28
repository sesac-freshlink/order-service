package com.freshlink.order.domain.payment;

import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** order_id는 유니크라 최대 1건이다 */
    Optional<Payment> findByOrderId(Long orderId);

    /** 점주 목록처럼 여러 주문의 결제를 한 번에 읽는다 (주문마다 조회하지 않게) */
    List<Payment> findByOrderIdIn(Collection<Long> orderIds);

    /** payment_key는 유니크다 (uk_payment_key). 다른 주문이 이미 쓴 키인지 본다 */
    boolean existsByPaymentKey(String paymentKey);
}
