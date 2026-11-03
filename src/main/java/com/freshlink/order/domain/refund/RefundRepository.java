package com.freshlink.order.domain.refund;

import java.time.OffsetDateTime;
import java.util.Collection;
import java.util.List;
import java.util.Optional;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

public interface RefundRepository extends JpaRepository<Refund, Long> {

    /** 그 주문의 시도 수. 새 시도 번호는 이 값 + 1이다 (uk_refund_order_attempt) */
    int countByOrderId(Long orderId);

    /** 그 주문의 마지막 시도 (주문 상세·점주 목록에 보여 준다) */
    Optional<Refund> findTopByOrderIdOrderByAttemptNoDesc(Long orderId);

    /** 여러 주문의 시도를 한 번에 읽는다. 부르는 쪽이 주문마다 마지막 시도를 고른다 */
    List<Refund> findByOrderIdIn(Collection<Long> orderIds);

    /** 결과 확인 조회할 차례가 된 그 상태들의 시도 ID, 예약 시각 이른 순 (인덱스 idx_refund_status_next_sync, V8) */
    @Query("select r.id from Refund r where r.status in :statuses and r.nextSyncAt <= :now order by r.nextSyncAt, r.id")
    List<Long> findSyncDueIds(@Param("statuses") Collection<RefundStatus> statuses, @Param("now") OffsetDateTime now,
                              Pageable pageable);

    /** 그 점포 주문의 환불 완료 시도 중 환불 시각이 start 이상 end 미만인 것 (점주 매출) */
    @Query("select new com.freshlink.order.domain.refund.CompletedRefund(r.amount, r.canceledAt)"
            + " from Refund r, Order o where o.id = r.orderId and o.storeId = :storeId and r.status = :status"
            + " and r.canceledAt >= :start and r.canceledAt < :end")
    List<CompletedRefund> findCompletedForStore(@Param("storeId") Long storeId, @Param("status") RefundStatus status,
                                                @Param("start") OffsetDateTime start, @Param("end") OffsetDateTime end);
}
