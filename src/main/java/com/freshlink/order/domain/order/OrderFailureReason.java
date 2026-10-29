package com.freshlink.order.domain.order;

/** 주문 실패 사유 (표 orders.failure_reason, V1 COMMENT 순서) */
public enum OrderFailureReason {
    OUT_OF_STOCK,
    PRODUCT_NOT_FOUND,
    INVALID_QUANTITY,
    PAYMENT_FAILED,
    EXPIRED,
    /** 결제 대기 중 픽업 기한이 지나 EXPIRED로 끝남 (A4, V4) */
    PICKUP_DEADLINE
}
