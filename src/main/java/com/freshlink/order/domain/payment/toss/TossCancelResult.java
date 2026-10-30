package com.freshlink.order.domain.payment.toss;

import java.time.OffsetDateTime;

/** 토스 결제 취소·취소 확인 조회 결과. 토스 오류 코드·메시지는 환불 기록과 로그에만 남기고 응답에 내리지 않는다 */
public record TossCancelResult(
        TossCancelOutcome outcome,
        String tossStatus,
        OffsetDateTime canceledAt,
        String errorCode,
        String errorMessage
) {

    /** canceledAt은 토스 cancels[]의 마지막 canceledAt. 없거나 해석할 수 없으면 null (부르는 쪽이 서버 시각을 쓴다) */
    public static TossCancelResult canceled(String tossStatus, OffsetDateTime canceledAt) {
        return new TossCancelResult(TossCancelOutcome.CANCELED, tossStatus, canceledAt, null, null);
    }

    public static TossCancelResult notCanceled(String tossStatus) {
        return new TossCancelResult(TossCancelOutcome.NOT_CANCELED, tossStatus, null, null, null);
    }

    public static TossCancelResult error(TossCancelOutcome outcome, String errorCode, String errorMessage) {
        return new TossCancelResult(outcome, null, null, errorCode, errorMessage);
    }
}
