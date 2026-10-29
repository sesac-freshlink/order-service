package com.freshlink.order.domain.order;

/** 주문 상태 (표 orders.status, V1 COMMENT 순서. PICKED_UP·PICKUP_EXPIRED는 V4) */
public enum OrderStatus {
    RESERVING,
    PAYMENT_WAITING,
    PAYMENT_APPROVING,
    PAID,
    OUT_OF_STOCK,
    PAYMENT_FAILED,
    EXPIRED,
    /** 점주 픽업 확인 (A5) */
    PICKED_UP,
    /** 결제 완료 뒤 픽업 기한이 지남 (A4). 자동 환불·재고 복원은 하지 않는다 */
    PICKUP_EXPIRED
}
