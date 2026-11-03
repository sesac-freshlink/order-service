package com.freshlink.order.domain.payment.toss;

import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 결제 취소 API 오류 코드 33개 + 멱등키 오류 2개 분류 (토스 오류 코드 문서 결제 취소 목록과 대조, 2026-10-30) */
class TossCancelErrorClassifierTest {

    private void assertAll(List<String> codes, TossCancelOutcome expected) {
        for (String code : codes) {
            assertThat(TossCancelErrorClassifier.classify(code)).as(code).isEqualTo(expected);
        }
    }

    @Test
    void 이미_취소됨은_조회로_확인한다() {
        assertAll(List.of("ALREADY_CANCELED_PAYMENT", "ALREADY_REFUND_PAYMENT"), TossCancelOutcome.ALREADY_CANCELED);
    }

    @Test
    void 취소되지_않았다고_문서가_말하는_코드는_실패() {
        assertAll(List.of("INVALID_REFUND_ACCOUNT_INFO", "EXCEED_CANCEL_AMOUNT_DISCOUNT_AMOUNT", "INVALID_REQUEST",
                "INVALID_REFUND_ACCOUNT_NUMBER", "INVALID_BANK", "NOT_MATCHES_REFUNDABLE_AMOUNT", "REFUND_REJECTED",
                "FORBIDDEN_BANK_REFUND_REQUEST", "FAILED_CLOSED_ACCOUNT_REFUND", "INVALID_CANCEL_REQUEST_ID",
                "UNAUTHORIZED_KEY", "NOT_CANCELABLE_AMOUNT", "FORBIDDEN_REQUEST", "NOT_CANCELABLE_PAYMENT",
                "EXCEED_MAX_REFUND_DUE", "NOT_ALLOWED_PARTIAL_REFUND_WAITING_DEPOSIT", "NOT_ALLOWED_PARTIAL_REFUND",
                "NOT_AVAILABLE_BANK", "INCORRECT_BASIC_AUTH_FORMAT", "NOT_CANCELABLE_PAYMENT_FOR_DORMANT_USER",
                "EXCEED_CANCEL_LIMIT", "NOT_FOUND_PAYMENT", "INVALID_IDEMPOTENCY_KEY"),
                TossCancelOutcome.DEFINITE_FAILURE);
    }

    @Test
    void 일시_오류와_처리_중은_결과_불명() {
        assertAll(List.of("PROVIDER_ERROR", "FORBIDDEN_CONSECUTIVE_REQUEST", "IDEMPOTENT_REQUEST_PROCESSING",
                "FAILED_INTERNAL_SYSTEM_PROCESSING", "FAILED_CARD_COMPANY_RESPONSE", "FAILED_REFUND_PROCESS",
                "FAILED_METHOD_HANDLING_CANCEL", "FAILED_PARTIAL_REFUND", "COMMON_ERROR",
                "FAILED_PAYMENT_INTERNAL_SYSTEM_PROCESSING"), TossCancelOutcome.UNKNOWN);
    }

    @Test
    void 목록에_없거나_없는_코드는_결과_불명() {
        assertThat(TossCancelErrorClassifier.classify("SOMETHING_NEW")).isEqualTo(TossCancelOutcome.UNKNOWN);
        assertThat(TossCancelErrorClassifier.classify(null)).isEqualTo(TossCancelOutcome.UNKNOWN);
    }
}
