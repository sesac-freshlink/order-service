package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 주문 응답(OR-02·OR-04)에 붙는 결제 내역. 승인을 시작하기 전(결제 기록이 없으면) 주문 응답에서는 null이다.
 * 승인 시각은 +09:00, 영수증 주소는 토스 receipt.url (없던 결제는 null)
 */
public record OrderPaymentResponse(
        String method,
        OffsetDateTime approvedAt,
        PaymentStatus status,
        String receiptUrl
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OrderPaymentResponse from(Payment payment) {
        if (payment == null) {
            return null;
        }
        OffsetDateTime approvedAt = payment.getApprovedAt() == null ? null
                : payment.getApprovedAt().atZoneSameInstant(KST).toOffsetDateTime();
        return new OrderPaymentResponse(payment.getMethod(), approvedAt, payment.getStatus(),
                payment.getReceiptUrl());
    }
}
