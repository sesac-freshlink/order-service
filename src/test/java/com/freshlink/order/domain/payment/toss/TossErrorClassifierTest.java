package com.freshlink.order.domain.payment.toss;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 결제 승인 API 오류 코드 49개 분류 (기능 명세 M5 표, 토스 오류 코드 문서 결제 승인 목록과 대조) */
class TossErrorClassifierTest {

    private void assertAll(List<String> codes, TossOutcome expected) {
        for (String code : codes) {
            assertThat(TossErrorClassifier.classify(code)).as(code).isEqualTo(expected);
        }
    }

    @Test
    void 기존_결과_확인() {
        assertAll(List.of("ALREADY_PROCESSED_PAYMENT", "ALREADY_PROCESSING_REQUEST"), TossOutcome.ALREADY_PROCESSED);
    }

    @Test
    void 결과_불명() {
        assertAll(List.of("PROVIDER_ERROR", "CARD_PROCESSING_ERROR", "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING",
                "FAILED_INTERNAL_SYSTEM_PROCESSING", "FAILED_CARD_COMPANY_RESPONSE", "UNKNOWN_PAYMENT_ERROR"),
                TossOutcome.UNKNOWN);
    }

    @Test
    void 확정_실패() {
        assertAll(List.of(
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
                "NOT_FOUND_PAYMENT_SESSION"), TossOutcome.DEFINITE_FAILURE);
    }

    @Test
    void 연동_설정_오류() {
        assertAll(List.of("INVALID_API_KEY", "UNAUTHORIZED_KEY", "INCORRECT_BASIC_AUTH_FORMAT", "INVALID_REQUEST",
                "FORBIDDEN_REQUEST", "INVALID_AUTHORIZE_AUTH", "NOT_FOUND_PAYMENT", "UNAPPROVED_ORDER_ID",
                "NOT_FOUND_TERMINAL_ID", "INVALID_UNREGISTERED_SUBMALL", "NOT_REGISTERED_BUSINESS"),
                TossOutcome.CONFIG_ERROR);
    }

    @Test
    void 목록에_없거나_없는_코드는_결과_불명() {
        assertThat(TossErrorClassifier.classify("SOMETHING_NEW")).isEqualTo(TossOutcome.UNKNOWN);
        assertThat(TossErrorClassifier.classify(null)).isEqualTo(TossOutcome.UNKNOWN);
        assertThat(TossErrorClassifier.classify("")).isEqualTo(TossOutcome.UNKNOWN);
    }
}
