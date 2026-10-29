package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** A3 고객 주문 내역 한 줄 (추가 명세 A3 표시 항목 + 픽업 기한). 시각은 +09:00 */
public record CustomerOrderSummaryResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        OrderFailureReason failureReason,
        String storeName,
        String productName,
        int quantity,
        Long amount,
        OffsetDateTime createdAt,
        OffsetDateTime pickupDeadline
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static CustomerOrderSummaryResponse from(Order order) {
        return new CustomerOrderSummaryResponse(order.getId(), order.getOrderNo(), order.getStatus(),
                order.getFailureReason(), order.getStoreName(), order.getProductName(), order.getQuantity(),
                order.getAmount(), kst(order.getCreatedAt()), kst(order.getPickupDeadline()));
    }

    private static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
