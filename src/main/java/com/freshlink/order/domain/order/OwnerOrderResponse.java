package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.Payment;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/** OR-04 점주 신규 주문 목록의 한 줄. 고객 정보는 내리지 않고 주문번호로 구분한다. 결제 시각은 +09:00, payment는 결제 내역 */
public record OwnerOrderResponse(
        Long orderId,
        String orderNo,
        String productName,
        int quantity,
        Long amount,
        OffsetDateTime paidAt,
        OrderPaymentResponse payment
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static OwnerOrderResponse from(Order order, Payment payment) {
        OffsetDateTime paidAt = order.getPaidAt() == null ? null
                : order.getPaidAt().atZoneSameInstant(KST).toOffsetDateTime();
        return new OwnerOrderResponse(order.getId(), order.getOrderNo(), order.getProductName(),
                order.getQuantity(), order.getAmount(), paidAt, OrderPaymentResponse.from(payment));
    }
}
