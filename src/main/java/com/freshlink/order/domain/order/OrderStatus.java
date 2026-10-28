package com.freshlink.order.domain.order;

/** 주문 상태 (표 orders.status, V1 COMMENT 순서) */
public enum OrderStatus {
    RESERVING,
    PAYMENT_WAITING,
    PAYMENT_APPROVING,
    PAID,
    OUT_OF_STOCK,
    PAYMENT_FAILED,
    EXPIRED
}
