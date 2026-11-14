package com.freshlink.order.domain.order;

import com.freshlink.order.domain.refund.RefundStatus;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.data.domain.Sort;

/**
 * A6 점주 주문 목록 구분 (추가 명세 A6). 구분마다 정렬 기준이 다르다.
 * PAID 주문은 환불 상태로 나눈다: 환불 NONE은 픽업 대기, 환불 처리·확인·실패는 취소·환불.
 * PICKUP_WAITING 은 결제 완료·픽업 신청·준비 완료를 한데 보여 주던 예전 구분이다. 배포된 화면이 새 구분으로 바뀔 때까지 그대로 둔다.
 * 결제·픽업 화면은 11/15부터 ALL·PICKUP_REQUESTED·READY_FOR_PICKUP 으로 나눠 본다
 */
public enum OwnerOrderTab {

    /** 픽업 대기: 결제 완료·환불 NONE과 픽업 준비 완료(N-02), 결제 완료 최신순 (MVP 신규 주문 목록과 같은 주문) */
    PICKUP_WAITING(EnumSet.of(OrderStatus.PAID, OrderStatus.PICKUP_REQUESTED, OrderStatus.READY_FOR_PICKUP),
            EnumSet.of(RefundStatus.NONE), "paidAt"),
    /** 픽업 신청: 고객이 픽업 신청해 점주가 [준비 완료]를 눌러야 하는 주문(환불 NONE), 결제 완료 최신순 (11/15) */
    PICKUP_REQUESTED(EnumSet.of(OrderStatus.PICKUP_REQUESTED), EnumSet.of(RefundStatus.NONE), "paidAt"),
    /** 준비 완료: 손님이 와서 [픽업 확인]을 기다리는 주문(N-02), 결제 완료 최신순 (11/15) */
    READY_FOR_PICKUP(EnumSet.of(OrderStatus.READY_FOR_PICKUP), EnumSet.allOf(RefundStatus.class), "paidAt"),
    /** 픽업 완료 최신순 */
    PICKED_UP(EnumSet.of(OrderStatus.PICKED_UP), EnumSet.allOf(RefundStatus.class), "pickedUpAt"),
    /** 취소·환불: 환불 처리·확인·실패와 REFUNDED, 취소 요청 최신순 */
    CANCELED(EnumSet.of(OrderStatus.PAID, OrderStatus.PICKUP_REQUESTED, OrderStatus.REFUNDED),
            EnumSet.of(RefundStatus.REQUESTED, RefundStatus.CHECKING, RefundStatus.FAILED), "cancelRequestedAt"),
    /** 기한 경과: 픽업 기한 최신순 */
    PICKUP_EXPIRED(EnumSet.of(OrderStatus.PICKUP_EXPIRED), EnumSet.allOf(RefundStatus.class), "pickupDeadline"),
    /** 전체: 위 구분에 나오는 결제 뒤 주문 모두(환불 상태 상관없음), 결제 완료 최신순 (11/15) */
    ALL(EnumSet.of(OrderStatus.PAID, OrderStatus.PICKUP_REQUESTED, OrderStatus.READY_FOR_PICKUP, OrderStatus.PICKED_UP,
            OrderStatus.PICKUP_EXPIRED, OrderStatus.REFUNDED), EnumSet.allOf(RefundStatus.class), "paidAt");

    private final Set<OrderStatus> statuses;
    private final Set<RefundStatus> paidRefundStatuses;
    private final String sortProperty;

    OwnerOrderTab(Set<OrderStatus> statuses, Set<RefundStatus> paidRefundStatuses, String sortProperty) {
        this.statuses = statuses;
        this.paidRefundStatuses = paidRefundStatuses;
        this.sortProperty = sortProperty;
    }

    public Set<OrderStatus> statuses() {
        return statuses;
    }

    /** PAID 주문 중 이 구분에 넣는 환불 상태. PAID가 아닌 주문에는 쓰지 않는다 */
    public Set<RefundStatus> paidRefundStatuses() {
        return paidRefundStatuses;
    }

    /** 정렬 기준 최신순, 같으면 주문 ID 큰 순 */
    public Sort sort() {
        return Sort.by(Sort.Order.desc(sortProperty), Sort.Order.desc("id"));
    }
}
