package com.freshlink.order.domain.order;

/** OR-01 주문 생성 응답. 재요청이면 현재 상태가 담긴다 */
public record CreateOrderResponse(Long orderId, String orderNo, OrderStatus status) {

    public static CreateOrderResponse from(Order order) {
        return new CreateOrderResponse(order.getId(), order.getOrderNo(), order.getStatus());
    }
}
