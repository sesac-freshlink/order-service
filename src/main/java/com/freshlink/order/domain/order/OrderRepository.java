package com.freshlink.order.domain.order;

import jakarta.persistence.LockModeType;
import java.util.Optional;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface OrderRepository extends JpaRepository<Order, Long> {

    /** (customer_id, request_id)는 유니크 키라 최대 1건이다 */
    Optional<Order> findByCustomerIdAndRequestId(String customerId, String requestId);

    /** 상태를 바꾸기 전에 행을 잠근다 (SELECT … FOR UPDATE). 같은 주문을 동시에 바꾸는 쪽지·스케줄러·결제가 차례로 처리된다 */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("select o from Order o where o.id = :id")
    Optional<Order> findByIdForUpdate(@Param("id") Long id);
}
