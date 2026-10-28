package com.freshlink.order.domain.order;

/** 주문 생성 결과. created=false면 같은 Idempotency-Key로 이미 만들어진 주문이다 */
public record CreateOrderResult(Order order, boolean created) {
}
