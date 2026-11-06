package com.freshlink.order.domain.order;

/** 주문 실패 사유 (표 orders.failure_reason, V1 COMMENT 순서) */
public enum OrderFailureReason {
    OUT_OF_STOCK,
    PRODUCT_NOT_FOUND,
    INVALID_QUANTITY,
    PAYMENT_FAILED,
    EXPIRED,
    /** 결제 대기 중 픽업 기한이 지나 EXPIRED로 끝남 (A4, V4) */
    PICKUP_DEADLINE,
    /** 마감세일 재고가 모자람(RESERVE_FAILED) 또는 받은 lot이 요청한 마감세일 수량과 맞지 않음(옛 inventory). 마감세일 함께 사기 */
    CLEARANCE_SOLD_OUT
}
