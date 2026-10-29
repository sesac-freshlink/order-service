package com.freshlink.order.domain.order;

import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Pageable;
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

    /** 점포의 한 상태 주문을 결제 시각 최신순으로 (인덱스 idx_orders_store_status_paid) */
    List<Order> findByStoreIdAndStatusOrderByPaidAtDescIdDesc(Long storeId, OrderStatus status);

    /** 결제 마감이 지난 주문, 마감·ID 순 (인덱스 idx_orders_status_expires) */
    @Query("select new com.freshlink.order.domain.order.DueOrder(o.id, o.paymentExpiresAt) from Order o"
            + " where o.status = :status and o.paymentExpiresAt <= :now order by o.paymentExpiresAt, o.id")
    List<DueOrder> findDue(@Param("status") OrderStatus status, @Param("now") OffsetDateTime now, Pageable pageable);

    /** findDue 의 다음 묶음: (afterDue, afterId) 바로 다음부터 */
    @Query("select new com.freshlink.order.domain.order.DueOrder(o.id, o.paymentExpiresAt) from Order o"
            + " where o.status = :status and o.paymentExpiresAt <= :now"
            + " and (o.paymentExpiresAt > :afterDue or (o.paymentExpiresAt = :afterDue and o.id > :afterId))"
            + " order by o.paymentExpiresAt, o.id")
    List<DueOrder> findDueAfter(@Param("status") OrderStatus status, @Param("now") OffsetDateTime now,
                                @Param("afterDue") OffsetDateTime afterDue, @Param("afterId") Long afterId,
                                Pageable pageable);

    /** 픽업 기한이 지난 주문, 기한·ID 순 (인덱스 idx_orders_status_pickup, V4) */
    @Query("select new com.freshlink.order.domain.order.DueOrder(o.id, o.pickupDeadline) from Order o"
            + " where o.status in :statuses and o.pickupDeadline < :now order by o.pickupDeadline, o.id")
    List<DueOrder> findPickupDue(@Param("statuses") Collection<OrderStatus> statuses, @Param("now") OffsetDateTime now,
                                 Pageable pageable);

    /** findPickupDue 의 다음 묶음: (afterDue, afterId) 바로 다음부터 */
    @Query("select new com.freshlink.order.domain.order.DueOrder(o.id, o.pickupDeadline) from Order o"
            + " where o.status in :statuses and o.pickupDeadline < :now"
            + " and (o.pickupDeadline > :afterDue or (o.pickupDeadline = :afterDue and o.id > :afterId))"
            + " order by o.pickupDeadline, o.id")
    List<DueOrder> findPickupDueAfter(@Param("statuses") Collection<OrderStatus> statuses,
                                      @Param("now") OffsetDateTime now, @Param("afterDue") OffsetDateTime afterDue,
                                      @Param("afterId") Long afterId, Pageable pageable);
}
