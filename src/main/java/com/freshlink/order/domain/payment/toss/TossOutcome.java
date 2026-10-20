package com.freshlink.order.domain.payment.toss;

/** 토스 승인 호출 결과 분류 (기능 명세 M5) */
public enum TossOutcome {
    /** 승인 성공 (status DONE) → PAID + CONFIRM */
    APPROVED,
    /** 확정 실패 → PAYMENT_FAILED + RELEASE */
    DEFINITE_FAILURE,
    /** ALREADY_PROCESSED_PAYMENT·ALREADY_PROCESSING_REQUEST → 승인 중 유지, OP-01로 실제 결과 확인 */
    ALREADY_PROCESSED,
    /** 결과 불명(타임아웃·일시 오류·목록에 없는 코드) → 승인 중 유지, 재고 해제 없음 */
    UNKNOWN,
    /** 연동·설정 오류 → 승인 중 유지, 오류 로그, 개발자 확인 */
    CONFIG_ERROR
}
