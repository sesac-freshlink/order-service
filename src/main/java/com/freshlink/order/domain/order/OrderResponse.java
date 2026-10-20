package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OR-02 주문 조회 응답. 시각은 모두 +09:00으로 바꿔 내린다.
 * DB에서 읽은 OffsetDateTime은 JVM 기본 시간대(파드는 UTC일 수 있다)로 오기 때문이다
 */
public record OrderResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        OrderFailureReason failureReason,
        Long storeId,
        String storeName,
        Long productId,
        String productName,
        int quantity,
        Long unitPrice,
        Long amount,
        OffsetDateTime paymentExpiresAt,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OrderResponse from(Order order) {
        return new OrderResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getFailureReason(),
                order.getStoreId(), order.getStoreName(), order.getProductId(), order.getProductName(),
                order.getQuantity(), order.getUnitPrice(), order.getAmount(),
                kst(order.getPaymentExpiresAt()), kst(order.getPaidAt()), kst(order.getCreatedAt()));
    }

    private static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
