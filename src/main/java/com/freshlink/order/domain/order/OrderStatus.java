package com.freshlink.order.domain.order;

import java.util.EnumMap;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;

/**
 * 주문 상태 (표 orders.status, V1 COMMENT 순서. PICKED_UP·PICKUP_EXPIRED는 V4, REFUNDED는 V8, CANCELED는 A7, READY_FOR_PICKUP은 N-02).
 * 허용되는 상태 전이는 이 enum의 표 한곳에서만 정한다(추가 명세 0.3, MVP 명세 2장).
 * 상태를 바꾸는 코드는 모두 {@link #canChangeTo(OrderStatus)}를 거친다(Order.changeStatus)
 */
public enum OrderStatus {
    RESERVING,
    PAYMENT_WAITING,
    PAYMENT_APPROVING,
    PAID,
    OUT_OF_STOCK,
    PAYMENT_FAILED,
    EXPIRED,
    /** 점주 픽업 확인 (A5) */
    PICKED_UP,
    /** 결제 완료 뒤 픽업 기한이 지남 (A4). 자동 환불·재고 복원은 하지 않는다 */
    PICKUP_EXPIRED,
    /** 전체 취소·전액 환불 완료 (A9). 재고는 RETURN으로 되돌린다 (A10) */
    REFUNDED,
    /** 결제 전 고객 직접 취소 (A7). 재고는 RELEASE(CANCELED)로 돌려준다 */
    CANCELED,
    /** 점주가 포장을 끝내고 "픽업 준비 완료"를 누름 (N-02). 고객에게 픽업 준비 완료 알림이 간다 */
    READY_FOR_PICKUP;

    private static final Map<OrderStatus, Set<OrderStatus>> ALLOWED = new EnumMap<>(OrderStatus.class);

    static {
        // 재고 확보 성공, 재고 확보 거절, OP-02 멈춘 주문 수동 종료
        ALLOWED.put(RESERVING, EnumSet.of(PAYMENT_WAITING, OUT_OF_STOCK, EXPIRED));
        // 승인 시작, 결제 시간 10분 초과 또는 결제 전 픽업 기한 경과(A4), 결제 전 고객 취소(A7)
        ALLOWED.put(PAYMENT_WAITING, EnumSet.of(PAYMENT_APPROVING, EXPIRED, CANCELED));
        // 승인 성공 확인, 확정 실패 확인 (결과 불명이면 그대로 둔다)
        ALLOWED.put(PAYMENT_APPROVING, EnumSet.of(PAID, PAYMENT_FAILED));
        // 점주 픽업 준비 완료(N-02), 픽업 기한 경과(A4), 전액 환불 성공 확인(A9). 픽업 확인은 준비 완료를 거쳐야 한다(10/30 경우 결정)
        ALLOWED.put(PAID, EnumSet.of(READY_FOR_PICKUP, PICKUP_EXPIRED, REFUNDED));
        // 점주 픽업 확인(A5), 픽업 기한 경과(A4)
        ALLOWED.put(READY_FOR_PICKUP, EnumSet.of(PICKED_UP, PICKUP_EXPIRED));
        // 나머지는 끝난 상태라 어디로도 가지 않는다
        for (OrderStatus status : values()) {
            ALLOWED.putIfAbsent(status, EnumSet.noneOf(OrderStatus.class));
        }
    }

    /** 이 상태에서 next로 바꿀 수 있는지 */
    public boolean canChangeTo(OrderStatus next) {
        return ALLOWED.get(this).contains(next);
    }
}
