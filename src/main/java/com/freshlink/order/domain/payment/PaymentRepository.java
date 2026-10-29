package com.freshlink.order.domain.payment;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface PaymentRepository extends JpaRepository<Payment, Long> {

    /** order_id는 유니크라 최대 1건이다 */
    Optional<Payment> findByOrderId(Long orderId);

    /** 점주 목록처럼 여러 주문의 결제를 한 번에 읽는다 (주문마다 조회하지 않게) */
    List<Payment> findByOrderIdIn(Collection<Long> orderIds);

    /** A11 자동 조회할 차례가 된 승인 중 결제의 주문 ID, 예약 시각 이른 순 (인덱스 idx_payment_status_next_sync, V6) */
    @Query("select p.orderId from Payment p where p.status = :status and p.nextSyncAt <= :now order by p.nextSyncAt, p.id")
    List<Long> findSyncDueOrderIds(@Param("status") PaymentStatus status, @Param("now") OffsetDateTime now,
                                   Pageable pageable);

    /** payment_key는 유니크다 (uk_payment_key). 다른 주문이 이미 쓴 키인지 본다 */
    boolean existsByPaymentKey(String paymentKey);
}
