package com.freshlink.order.domain.order;

import com.freshlink.order.domain.refund.RefundStatus;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.data.domain.Sort;

/**
 * A6 점주 주문 목록 구분 (추가 명세 A6). 구분마다 정렬 기준이 다르다.
 * PAID 주문은 환불 상태로 나눈다: 환불 NONE은 픽업 대기, 환불 처리·확인·실패는 취소·환불
 */
public enum OwnerOrderTab {

    /** 픽업 대기: 결제 완료·환불 NONE과 픽업 준비 완료(N-02), 결제 완료 최신순 (MVP 신규 주문 목록과 같은 주문) */
    PICKUP_WAITING(EnumSet.of(OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP), EnumSet.of(RefundStatus.NONE), "paidAt"),
    /** 픽업 완료 최신순 */
    PICKED_UP(EnumSet.of(OrderStatus.PICKED_UP), EnumSet.allOf(RefundStatus.class), "pickedUpAt"),
    /** 취소·환불: 환불 처리·확인·실패와 REFUNDED, 취소 요청 최신순 */
    CANCELED(EnumSet.of(OrderStatus.PAID, OrderStatus.REFUNDED),
            EnumSet.of(RefundStatus.REQUESTED, RefundStatus.CHECKING, RefundStatus.FAILED), "cancelRequestedAt"),
    /** 기한 경과: 픽업 기한 최신순 */
    PICKUP_EXPIRED(EnumSet.of(OrderStatus.PICKUP_EXPIRED), EnumSet.allOf(RefundStatus.class), "pickupDeadline");

    private final Set<OrderStatus> statuses;
    private final Set<RefundStatus> paidRefundStatuses;
    private final String sortProperty;

    OwnerOrderTab(Set<OrderStatus> statuses, Set<RefundStatus> paidRefundStatuses, String sortProperty) {
        this.statuses = statuses;
        this.paidRefundStatuses = paidRefundStatuses;
        this.sortProperty = sortProperty;
    }

    public Set<OrderStatus> statuses() {
        return statuses;
    }

    /** PAID 주문 중 이 구분에 넣는 환불 상태. PAID가 아닌 주문에는 쓰지 않는다 */
    public Set<RefundStatus> paidRefundStatuses() {
        return paidRefundStatuses;
    }

    /** 정렬 기준 최신순, 같으면 주문 ID 큰 순 */
    public Sort sort() {
        return Sort.by(Sort.Order.desc(sortProperty), Sort.Order.desc("id"));
    }
}
