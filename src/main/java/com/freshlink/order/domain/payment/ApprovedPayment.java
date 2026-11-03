package com.freshlink.order.domain.payment;

import java.time.OffsetDateTime;

/** 매출 집계용: 승인 금액과 승인 시각 (PaymentRepository.findApprovedForStore) */
public record ApprovedPayment(long amount, OffsetDateTime approvedAt) {
}
