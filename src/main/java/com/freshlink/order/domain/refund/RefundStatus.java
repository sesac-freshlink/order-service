package com.freshlink.order.domain.refund;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 환불 상태 (표 orders.refund_status·refund.status, V8). 주문 상태와 따로 둔다(추가 명세 0.2).
 * 허용되는 전이는 이 표 한곳에서만 정한다(추가 명세 0.3 둘째 표). refund 표의 한 시도는 NONE이 되지 않는다
 */
public enum RefundStatus {
    /** 환불 요청 없음 */
    NONE,
    /** 취소·환불 처리 중 */
    REQUESTED,
    /** 실제 환불 결과 확인 중 */
    CHECKING,
    /** 명확한 환불 실패. 재신청·재시도 가능 */
    FAILED,
    /** 환불 완료. 주문은 REFUNDED */
    COMPLETED;

    private static final Map<RefundStatus, Set<RefundStatus>> ALLOWED = new EnumMap<>(RefundStatus.class);

    static {
        // 유효한 취소 요청
        ALLOWED.put(NONE, EnumSet.of(REQUESTED));
        // 유효한 재신청·재시도
        ALLOWED.put(FAILED, EnumSet.of(REQUESTED));
        // 토스 취소 성공 확인, 명확한 환불 실패, 환불 결과 불명
        ALLOWED.put(REQUESTED, EnumSet.of(COMPLETED, FAILED, CHECKING));
        // 환불 성공 확인, 최종 실패 확인
        ALLOWED.put(CHECKING, EnumSet.of(COMPLETED, FAILED));
        ALLOWED.put(COMPLETED, EnumSet.noneOf(RefundStatus.class));
    }

    public boolean canChangeTo(RefundStatus next) {
        return ALLOWED.get(this).contains(next);
    }

    /** 처리 중·확인 중. 이때는 새 환불·픽업을 받지 않는다 */
    public boolean inProgress() {
        return this == REQUESTED || this == CHECKING;
    }
}
