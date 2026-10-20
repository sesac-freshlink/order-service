package com.freshlink.order.domain.payment;

/** 결제 기록 상태 (표 payment.status, V1 COMMENT) */
public enum PaymentStatus {
    APPROVING,
    DONE,
    FAILED
}
