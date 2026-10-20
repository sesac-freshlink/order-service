package com.freshlink.order.domain.payment.toss;

import java.time.OffsetDateTime;

/** 토스 결제 조회(GET /v1/payments/{paymentKey}) 결과. 조회 실패·결제 없음은 found=false (실패로 단정하지 않는다) */
public record TossLookupResult(
        boolean found,
        String status,
        String method,
        OffsetDateTime approvedAt,
        String errorCode,
        String errorMessage
) {

    public static TossLookupResult found(String status, String method, OffsetDateTime approvedAt) {
        return new TossLookupResult(true, status, method, approvedAt, null, null);
    }

    public static TossLookupResult notFound(String errorCode, String errorMessage) {
        return new TossLookupResult(false, null, null, null, errorCode, errorMessage);
    }
}
