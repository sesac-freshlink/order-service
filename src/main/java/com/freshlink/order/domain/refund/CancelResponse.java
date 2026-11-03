package com.freshlink.order.domain.refund;

import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderStatus;

/**
 * 고객·점주 취소 요청 응답. 결과는 refundStatus로 알린다:
 * COMPLETED(주문 REFUNDED) / FAILED(재신청 가능) / CHECKING(결과 확인 중, 서버가 이어서 확인)
 */
public record CancelResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        RefundStatus refundStatus,
        RefundResponse refund
) {

    public static CancelResponse from(Order order, Refund refund) {
        return new CancelResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getRefundStatus(),
                RefundResponse.from(refund));
    }
}
