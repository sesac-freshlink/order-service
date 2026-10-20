package com.freshlink.order.domain.order;

/** OP-02 응답 */
public record OrderCloseResponse(Long orderId, OrderStatus before, OrderStatus after) {
}
