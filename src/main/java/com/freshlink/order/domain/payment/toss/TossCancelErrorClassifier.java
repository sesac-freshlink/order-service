package com.freshlink.order.domain.payment.toss;

import java.util.Set;

/**
 * 결제 취소 API(POST /v1/payments/{paymentKey}/cancel) 오류 코드 분류. 승인 분류(TossErrorClassifier)와 따로 둔다(추가 명세 A9).
 * 코드 목록은 토스 오류 코드 문서의 결제 취소 목록 33개와 멱등키 오류 2개를 대조했다 (2026-10-30).
 * 취소가 일어나지 않았다고 문서가 말하는 코드만 실패로 본다. 일시 오류(5xx·PROVIDER_ERROR 등)와 목록에 없는 코드는 결과 불명이다
 */
public final class TossCancelErrorClassifier {

    private static final Set<String> ALREADY_CANCELED = Set.of(
            "ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT");

    private static final Set<String> DEFINITE_FAILURE = Set.of(
            // 400 요청·계좌
            "INVALID_REFUND_ACCOUNT_INFO", "EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT", "INVALID_REQUEST",
            "INVALID_REFUND_ACCOUNT_NUMBER", "INVALID_BANK", "NOT_MATCHES_REFUNDABLE_AMOUNT", "REFUND_REJECTED",
            "FORBIDDEN_BANK_REFUND_REQUEST", "FAILED_CLOSED_ACCOUNT_REFUND", "INVALID_CANCEL_REQUEST_ID",
            // 401·403 인증·취소 불가
            "UNAUTHORIZED_KEY", "NOT_CANCELABLE_AMOUNT", "FORBIDDEN_REQUEST", "NOT_CANCELABLE_PAYMENT",
            "EXCEED_MAX_REFUND_DUE", "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT", "NOT_ALLOWED_PARTIAL_REFUND",
            "NOT_AVAILABLE_BANK", "INCORRECT_BASIC_AUTH_FORMAT", "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER",
            "EXCEED_CANCEL_LIMIT",
            // 404
            "NOT_FOUND_PAYMENT",
            // 멱등키 형식 오류 (요청이 처리되지 않음)
            "INVALID_IDEMPOTENCY_KEY");

    private TossCancelErrorClassifier() {
    }

    /**
     * 결과 불명: PROVIDER_ERROR, FORBIDDEN_CONSECUTIVE_REQUEST, IDEMPOTENT_REQUEST_PROCESSING(첫 요청 처리 중),
     * 500대(FAILED_INTERNAL_SYSTEM_PROCESSING, FAILED_CARD_COMPANY_RESPONSE, FAILED_REFUND_PROCESS,
     * FAILED_METHOD_HANDLING_CANCEL, FAILED_PARTIAL_REFUND, COMMON_ERROR, FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING),
     * 그리고 목록에 없는 코드
     */
    public static TossCancelOutcome classify(String code) {
        if (code == null) {
            return TossCancelOutcome.UNKNOWN;
        }
        if (ALREADY_CANCELED.contains(code)) {
            return TossCancelOutcome.ALREADY_CANCELED;
        }
        if (DEFINITE_FAILURE.contains(code)) {
            return TossCancelOutcome.DEFINITE_FAILURE;
        }
        return TossCancelOutcome.UNKNOWN;
    }
}
