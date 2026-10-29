package com.freshlink.order.domain.order;

import java.util.EnumSet;
import java.util.Set;
import org.springframework.data.domain.Sort;

/**
 * A6 점주 주문 목록 구분 (추가 명세 A6). 구분마다 정렬 기준이 다르다.
 * 취소·환불은 환불(A7~A9)을 만들면 채운다. 지금은 해당 주문이 없어 늘 빈 목록이다
 */
public enum OwnerOrderTab {

    /** 픽업 대기: 결제 완료 최신순 (MVP 신규 주문 목록과 같은 주문) */
    PICKUP_WAITING(EnumSet.of(OrderStatus.PAID), "paidAt"),
    /** 픽업 완료 최신순 */
    PICKED_UP(EnumSet.of(OrderStatus.PICKED_UP), "pickedUpAt"),
    /** 취소·환불: 환불 진행·확인·실패와 REFUNDED (A9 전까지 비어 있음) */
    CANCELED(EnumSet.noneOf(OrderStatus.class), "createdAt"),
    /** 기한 경과: 픽업 기한 최신순 */
    PICKUP_EXPIRED(EnumSet.of(OrderStatus.PICKUP_EXPIRED), "pickupDeadline");

    private final Set<OrderStatus> statuses;
    private final String sortProperty;

    OwnerOrderTab(Set<OrderStatus> statuses, String sortProperty) {
        this.statuses = statuses;
        this.sortProperty = sortProperty;
    }

    public Set<OrderStatus> statuses() {
        return statuses;
    }

    /** 정렬 기준 최신순, 같으면 주문 ID 큰 순 */
    public Sort sort() {
        return Sort.by(Sort.Order.desc(sortProperty), Sort.Order.desc("id"));
    }
}
