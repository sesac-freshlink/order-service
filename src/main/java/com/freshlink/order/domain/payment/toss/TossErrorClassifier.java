package com.freshlink.order.domain.payment.toss;

import java.util.Set;

/**
 * 결제 승인 API(POST /v1/payments/confirm) 오류 코드 분류. HTTP 상태코드가 아니라 code로 나눈다
 * (예: PROVIDER_ERROR는 400이지만 일시 오류다). 코드 목록은 기능 명세 M5 표이고 토스 오류 코드 문서의
 * 결제 승인 목록 49개와 대조했다 (2026-10-20)
 */
public final class TossErrorClassifier {

    private static final Set<String> ALREADY_PROCESSED = Set.of(
            "ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST");

    private static final Set<String> DEFINITE_FAILURE = Set.of(
            // 카드
            "REJECT_CARD_PAYMENT", "REJECT_CARD_COMPANY", "INVALID_REJECT_CARD", "INVALID_STOPPED_CARD",
            "INVALID_CARD_LOST_OR_STOLEN", "INVALID_CARD_EXPIRATION", "INVALID_CARD_NUMBER",
            "NOT_ALLOWED_POINT_USE", "EXCEED_MAX_AUTH_COUNT",
            // 한도·횟수
            "EXCEED_MAX_DAILY_PAYMENT_COUNT", "EXCEED_MAX_PAYMENT_AMOUNT", "EXCEED_MAX_AMOUNT",
            "EXCEED_MAX_ONE_DAY_AMOUNT", "EXCEED_MAX_MONTHLY_PAYMENT_AMOUNT",
            "EXCEED_MAX_ONE_DAY_WITHDRAW_AMOUNT", "EXCEED_MAX_ONE_TIME_WITHDRAW_AMOUNT",
            // 할부
            "EXCEED_MAX_CARD_INSTALLMENT_PLAN", "NOT_SUPPORTED_INSTALLMENT_PLAN_CARD_OR_MERCHANT",
            "INVALID_CARD_INSTALLMENT_PLAN", "NOT_SUPPORTED_MONTHLY_INSTALLMENT_PLAN",
            // 계좌·간편결제
            "REJECT_ACCOUNT_PAYMENT", "RESTRICTED_TRANSFER_ACCOUNT", "INVALID_ACCOUNT_INFO_RE_REGISTER",
            "REJECT_TOSSPAY_INVALID_ACCOUNT", "INVALID_PASSWORD",
            // 기타 결제 조건
            "BELOW_MINIMUM_AMOUNT", "NOT_AVAILABLE_PAYMENT", "NOT_AVAILABLE_BANK", "FDS_ERROR",
            // 결제 세션 만료
            "NOT_FOUND_PAYMENT_SESSION");

    private static final Set<String> CONFIG_ERROR = Set.of(
            "INVALID_API_KEY", "UNAUTHORIZED_KEY", "INCORRECT_BASIC_AUTH_FORMAT", "INVALID_REQUEST",
            "FORBIDDEN_REQUEST", "INVALID_AUTHORIZE_AUTH", "NOT_FOUND_PAYMENT", "UNAPPROVED_ORDER_ID",
            "NOT_FOUND_TERMINAL_ID", "INVALID_UNREGISTERED_SUBMALL", "NOT_REGISTERED_BUSINESS");

    private TossErrorClassifier() {
    }

    /** 결과 불명 코드(PROVIDER_ERROR 등)와 목록에 없는 코드는 모두 UNKNOWN이다 */
    public static TossOutcome classify(String code) {
        if (code == null) {
            return TossOutcome.UNKNOWN;
        }
        if (ALREADY_PROCESSED.contains(code)) {
            return TossOutcome.ALREADY_PROCESSED;
        }
        if (DEFINITE_FAILURE.contains(code)) {
            return TossOutcome.DEFINITE_FAILURE;
        }
        if (CONFIG_ERROR.contains(code)) {
            return TossOutcome.CONFIG_ERROR;
        }
        return TossOutcome.UNKNOWN;
    }
}
