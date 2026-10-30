package com.freshlink.order.domain.order;

import com.freshlink.order.domain.refund.RefundStatus;
import jakarta.persistence.LockModeType;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import java.time.OffsetDateTime;
import org.springframework.data.domain.Page;
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

    /** 점포의 한 상태·환불 상태 주문을 결제 시각 최신순으로 (인덱스 idx_orders_store_status_paid) */
    List<Order> findByStoreIdAndStatusAndRefundStatusOrderByPaidAtDescIdDesc(Long storeId, OrderStatus status,
                                                                             RefundStatus refundStatus);

    /**
     * A3 고객 주문 내역: 본인 주문 중 그 상태들(PAID는 그 환불 상태들만), 만든 시각 start 이상 end 미만,
     * 정렬·페이지는 pageable (인덱스 idx_orders_customer_created, V5)
     */
    @Query("select o from Order o where o.customerId = :customerId and o.status in :statuses"
            + " and (o.status <> :paid or o.refundStatus in :paidRefundStatuses)"
            + " and o.createdAt >= :start and o.createdAt < :end")
    Page<Order> findForCustomer(@Param("customerId") String customerId,
                                @Param("statuses") Collection<OrderStatus> statuses, @Param("paid") OrderStatus paid,
                                @Param("paidRefundStatuses") Collection<RefundStatus> paidRefundStatuses,
                                @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end,
                                Pageable pageable);

    /**
     * A6 점주 주문 목록: 그 점포 주문 중 그 상태들(PAID는 그 환불 상태들만), 만든 시각 start 이상 end 미만,
     * 정렬·페이지는 pageable (인덱스 idx_orders_store_status_paid 앞부분)
     */
    @Query("select o from Order o where o.storeId = :storeId and o.status in :statuses"
            + " and (o.status <> :paid or o.refundStatus in :paidRefundStatuses)"
            + " and o.createdAt >= :start and o.createdAt < :end")
    Page<Order> findForStore(@Param("storeId") Long storeId,
                             @Param("statuses") Collection<OrderStatus> statuses, @Param("paid") OrderStatus paid,
                             @Param("paidRefundStatuses") Collection<RefundStatus> paidRefundStatuses,
                             @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end,
                             Pageable pageable);

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

    /** A12 재고 확인 결과를 기준 시각까지 못 받았고 아직 경고하지 않은 주문 ID, 오래된 순 (인덱스 idx_orders_status_created, V7) */
    @Query("select o.id from Order o where o.status = :status and o.createdAt <= :before and o.reservingAlertedAt is null"
            + " order by o.createdAt, o.id")
    List<Long> findStuckReservingIds(@Param("status") OrderStatus status, @Param("before") OffsetDateTime before,
                                     Pageable pageable);

    /** 픽업 기한이 지난 주문 중 그 환불 상태인 것, 기한·ID 순 (인덱스 idx_orders_status_pickup, V4) */
    @Query("select new com.freshlink.order.domain.order.DueOrder(o.id, o.pickupDeadline) from Order o"
            + " where o.status in :statuses and o.refundStatus = :refundStatus and o.pickupDeadline < :now"
            + " order by o.pickupDeadline, o.id")
    List<DueOrder> findPickupDue(@Param("statuses") Collection<OrderStatus> statuses,
                                 @Param("refundStatus") RefundStatus refundStatus, @Param("now") OffsetDateTime now,
                                 Pageable pageable);

    /** findPickupDue 의 다음 묶음: (afterDue, afterId) 바로 다음부터 */
    @Query("select new com.freshlink.order.domain.order.DueOrder(o.id, o.pickupDeadline) from Order o"
            + " where o.status in :statuses and o.refundStatus = :refundStatus and o.pickupDeadline < :now"
            + " and (o.pickupDeadline > :afterDue or (o.pickupDeadline = :afterDue and o.id > :afterId))"
            + " order by o.pickupDeadline, o.id")
    List<DueOrder> findPickupDueAfter(@Param("statuses") Collection<OrderStatus> statuses,
                                      @Param("refundStatus") RefundStatus refundStatus,
                                      @Param("now") OffsetDateTime now, @Param("afterDue") OffsetDateTime afterDue,
                                      @Param("afterId") Long afterId, Pageable pageable);
}
