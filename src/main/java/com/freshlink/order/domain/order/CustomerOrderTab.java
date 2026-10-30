package com.freshlink.order.domain.order;

import java.util.EnumSet;
import java.util.Set;

/**
 * A3 고객 주문 내역 구분 (추가 명세 A3). 주문일시 최신순.
 * 환불(A7~A9)이 생기면 CANCELED에 CANCELED·REFUNDED와 환불 진행 주문이 더해진다.
 * OUT_OF_STOCK·PAYMENT_FAILED는 고객 목록에 넣지 않는다
 */
public enum CustomerOrderTab {

    /** 픽업 대기: 결제 완료와 픽업 준비 완료(N-02) */
    PICKUP_WAITING(EnumSet.of(OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP)),
    /** 픽업 완료 */
    PICKED_UP(EnumSet.of(OrderStatus.PICKED_UP)),
    /** 취소·환불: 지금은 결제 전 만료(결제 시간·픽업 기한)만 */
    CANCELED(EnumSet.of(OrderStatus.EXPIRED)),
    /** 전체: 위 주문과 진행 중 주문, 결제 뒤 기한 경과(PICKUP_EXPIRED). 이 구분에만 기한 경과가 나온다 */
    ALL(EnumSet.of(OrderStatus.RESERVING, OrderStatus.PAYMENT_WAITING, OrderStatus.PAYMENT_APPROVING,
            OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP, OrderStatus.EXPIRED,
            OrderStatus.PICKUP_EXPIRED));

    private final Set<OrderStatus> statuses;

    CustomerOrderTab(Set<OrderStatus> statuses) {
        this.statuses = statuses;
    }

    public Set<OrderStatus> statuses() {
        return statuses;
    }
}
