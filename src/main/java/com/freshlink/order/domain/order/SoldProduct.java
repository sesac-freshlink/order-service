package com.freshlink.order.domain.order;

/**
 * 하루 상품별 매출 집계용: 결제 승인(또는 환불 완료)된 주문 한 건의 상품·수량·금액.
 * amount는 실제 결제 금액(마감세일 할인 반영) 또는 환불 금액이다
 */
public record SoldProduct(Long productId, String productName, int quantity, int clearanceQuantity, long amount) {
}
