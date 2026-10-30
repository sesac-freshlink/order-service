package com.freshlink.order.domain.refund;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * 취소·환불 표시 (추가 명세 A8 "취소·환불 표시"): 요청자·취소 사유·환불금액·처리 상태·취소 요청 시각·취소 완료 시각.
 * 그 주문의 마지막 시도를 보여 준다. 금액 이름은 처리 중·실패·완료 모두 refundAmount(환불금액)다. 시각은 +09:00
 */
public record RefundResponse(
        CancelRequester requestedBy,
        CancelReason reason,
        String reasonLabel,
        String reasonDetail,
        long refundAmount,
        RefundStatus status,
        OffsetDateTime requestedAt,
        OffsetDateTime canceledAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 시도가 없으면 null */
    public static RefundResponse from(Refund refund) {
        if (refund == null) {
            return null;
        }
        return new RefundResponse(refund.getRequestedBy(), refund.getReason(), refund.getReason().label(),
                refund.getReasonDetail(), refund.getAmount(), refund.getStatus(), kst(refund.getRequestedAt()),
                kst(refund.getCanceledAt()));
    }

    private static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
