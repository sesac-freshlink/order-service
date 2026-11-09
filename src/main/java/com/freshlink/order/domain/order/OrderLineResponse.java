package com.freshlink.order.domain.order;

import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * 주문 한 줄(마감세일분·기본분). order_lot을 마감세일 여부와 단가로 묶는다. 재고를 잡기 전(RESERVED 전)이면 줄이 없다.
 * amount는 그 줄 금액(단가 × 수량)
 */
public record OrderLineResponse(boolean clearance, int quantity, Long unitPrice, Long amount) {

    private record Key(boolean clearance, Long unitPrice) {
    }

    /** 마감세일 줄을 먼저 둔다. lot 단가가 없으면 주문의 기본 단가를 쓴다 */
    public static List<OrderLineResponse> from(Order order, List<OrderLot> lots) {
        if (lots == null || lots.isEmpty() || order.getUnitPrice() == null) {
            return List.of();
        }
        Map<Key, Integer> quantities = new LinkedHashMap<>();
        for (OrderLot lot : lots) {
            Long price = lot.getUnitPrice() != null ? lot.getUnitPrice() : order.getUnitPrice();
            quantities.merge(new Key(lot.isClearance(), price), lot.getQuantity(), Integer::sum);
        }
        return quantities.entrySet().stream()
                .sorted(Comparator.comparing((Map.Entry<Key, Integer> e) -> !e.getKey().clearance()))
                .map(e -> new OrderLineResponse(e.getKey().clearance(), e.getValue(), e.getKey().unitPrice(),
                        e.getKey().unitPrice() * e.getValue()))
                .toList();
    }
}
