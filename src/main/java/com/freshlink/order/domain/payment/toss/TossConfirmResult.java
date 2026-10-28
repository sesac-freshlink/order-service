package com.freshlink.order.domain.payment.toss;

import java.time.OffsetDateTime;

/** 토스 승인 호출 결과. 토스 오류 코드·메시지는 결제 기록과 로그에만 남기고 응답에 내리지 않는다 */
public record TossConfirmResult(
        TossOutcome outcome,
        String tossStatus,
        String method,
        OffsetDateTime approvedAt,
        String receiptUrl,
        String errorCode,
        String errorMessage
) {

    /** receiptUrl은 토스 receipt.url. 없으면 null */
    public static TossConfirmResult approved(String tossStatus, String method, OffsetDateTime approvedAt,
                                             String receiptUrl) {
        return new TossConfirmResult(TossOutcome.APPROVED, tossStatus, method, approvedAt, receiptUrl, null, null);
    }

    public static TossConfirmResult error(TossOutcome outcome, String errorCode, String errorMessage) {
        return new TossConfirmResult(outcome, null, null, null, null, errorCode, errorMessage);
    }
}
