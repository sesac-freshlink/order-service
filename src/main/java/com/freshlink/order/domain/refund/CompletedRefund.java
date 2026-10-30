package com.freshlink.order.domain.refund;

import java.time.OffsetDateTime;

/** 매출 집계용: 환불 금액과 환불 시각 (RefundRepository.findCompletedForStore) */
public record CompletedRefund(long amount, OffsetDateTime canceledAt) {
}
