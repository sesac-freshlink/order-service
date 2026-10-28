package com.freshlink.order.domain.payment;

import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** OR-03 응답. 처리 결과는 status로 알린다 (PAID·PAYMENT_FAILED·PAYMENT_APPROVING). 결제 시각은 +09:00 */
public record PaymentConfirmResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        String storeName,
        String productName,
        int quantity,
        Long amount,
        OffsetDateTime paidAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PaymentConfirmResponse from(Order order) {
        OffsetDateTime paidAt = order.getPaidAt() == null ? null
                : order.getPaidAt().atZoneSameInstant(KST).toOffsetDateTime();
        return new PaymentConfirmResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getStoreName(),
                order.getProductName(), order.getQuantity(), order.getAmount(), paidAt);
    }
}
