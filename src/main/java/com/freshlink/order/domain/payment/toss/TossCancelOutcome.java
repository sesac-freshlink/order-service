package com.freshlink.order.domain.payment.toss;

/** 토스 결제 취소·취소 확인 조회 결과 분류 (추가 명세 A9: 취소 API 오류 코드는 승인 API와 따로 분류한다) */
public enum TossCancelOutcome {
    /** 취소 성공 확인 (결제 status CANCELED) → 환불 완료 */
    CANCELED,
    /** 토스가 취소를 거절했다(취소되지 않음) → 환불 실패, 재신청 가능 */
    DEFINITE_FAILURE,
    /** 이미 취소·환불된 결제(ALREADY_CANCELED_PAYMENT 등) → 조회로 실제 취소를 확인한다 */
    ALREADY_CANCELED,
    /** 결과 불명(타임아웃·일시 오류·목록에 없는 코드) → 확인 중, 조회로 확인한다 */
    UNKNOWN,
    /** 조회 결과 아직 취소되지 않은 결제 (status DONE). 조회에서만 나온다 */
    NOT_CANCELED
}
