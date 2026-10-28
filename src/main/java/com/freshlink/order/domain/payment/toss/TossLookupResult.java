package com.freshlink.order.domain.payment.toss;

import java.time.OffsetDateTime;

/** 토스 결제 조회(GET /v1/payments/{paymentKey}) 결과. 조회 실패·결제 없음은 found=false (실패로 단정하지 않는다) */
public record TossLookupResult(
        boolean found,
        String status,
        String method,
        OffsetDateTime approvedAt,
        String receiptUrl,
        String orderId,
        Long totalAmount,
        String errorCode,
        String errorMessage
) {

    /** receiptUrl은 토스 receipt.url, orderId는 토스 결제의 주문번호(우리 orderNo), totalAmount는 결제 금액 */
    public static TossLookupResult found(String status, String method, OffsetDateTime approvedAt,
                                         String receiptUrl, String orderId, Long totalAmount) {
        return new TossLookupResult(true, status, method, approvedAt, receiptUrl, orderId, totalAmount, null, null);
    }

    public static TossLookupResult notFound(String errorCode, String errorMessage) {
        return new TossLookupResult(false, null, null, null, null, null, null, errorCode, errorMessage);
    }
}
