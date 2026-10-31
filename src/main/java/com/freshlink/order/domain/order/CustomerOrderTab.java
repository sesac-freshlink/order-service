package com.freshlink.order.domain.order;

import com.freshlink.order.domain.refund.RefundStatus;
import java.util.EnumSet;
import java.util.Set;

/**
 * A3 고객 주문 내역 구분 (추가 명세 A3). 주문일시 최신순.
 * 픽업 대기 주문(PAID·READY_FOR_PICKUP)은 환불 상태로 나눈다: 환불 NONE은 픽업 대기, 환불 처리·확인·실패는 취소·환불.
 * 결제 전 취소(CANCELED, A7)도 취소·환불에 나온다.
 * OUT_OF_STOCK·PAYMENT_FAILED는 고객 목록에 넣지 않는다
 */
public enum CustomerOrderTab {

    /** 픽업 대기: 결제 완료·환불 NONE과 픽업 준비 완료(N-02) */
    PICKUP_WAITING(EnumSet.of(OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP), EnumSet.of(RefundStatus.NONE)),
    /** 픽업 완료 */
    PICKED_UP(EnumSet.of(OrderStatus.PICKED_UP), EnumSet.allOf(RefundStatus.class)),
    /** 취소·환불: 결제 전 만료(결제 시간·픽업 기한)·고객 취소(A7), 환불 완료(REFUNDED), 환불 처리·확인·실패 주문 */
    CANCELED(EnumSet.of(OrderStatus.EXPIRED, OrderStatus.CANCELED, OrderStatus.REFUNDED, OrderStatus.PAID,
            OrderStatus.READY_FOR_PICKUP),
            EnumSet.of(RefundStatus.REQUESTED, RefundStatus.CHECKING, RefundStatus.FAILED)),
    /** 전체: 위 주문과 진행 중 주문, 결제 뒤 기한 경과(PICKUP_EXPIRED). 이 구분에만 기한 경과가 나온다 */
    ALL(EnumSet.of(OrderStatus.RESERVING, OrderStatus.PAYMENT_WAITING, OrderStatus.PAYMENT_APPROVING,
            OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP, OrderStatus.EXPIRED,
            OrderStatus.PICKUP_EXPIRED, OrderStatus.CANCELED, OrderStatus.REFUNDED), EnumSet.allOf(RefundStatus.class));

    private final Set<OrderStatus> statuses;
    private final Set<RefundStatus> paidRefundStatuses;

    CustomerOrderTab(Set<OrderStatus> statuses, Set<RefundStatus> paidRefundStatuses) {
        this.statuses = statuses;
        this.paidRefundStatuses = paidRefundStatuses;
    }

    public Set<OrderStatus> statuses() {
        return statuses;
    }

    /** 픽업 대기(PAID·READY_FOR_PICKUP) 주문 중 이 구분에 넣는 환불 상태. 그 밖의 주문에는 쓰지 않는다 */
    public Set<RefundStatus> paidRefundStatuses() {
        return paidRefundStatuses;
    }
}
