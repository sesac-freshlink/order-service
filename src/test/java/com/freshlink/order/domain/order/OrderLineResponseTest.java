package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 주문 줄: order_lot을 마감세일 여부·단가로 묶고 마감세일 줄을 먼저 둔다 */
class OrderLineResponseTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-06T10:00:00+09:00");
    private static final OffsetDateTime EXPIRY = OffsetDateTime.parse("2026-11-06T23:59:59+09:00");

    private Order reservedOrder(int quantity, int clearance) {
        Order order = Order.create("c", "k", 1L, "프레시 강남점", 15L, quantity, clearance);
        order.reserve("꿀밤고구마 800g", 4990L, 3490L + 4990L, NOW);
        return order;
    }

    @Test
    void 마감세일_줄을_먼저_두고_lot_단가가_없으면_기본_단가() {
        Order order = reservedOrder(3, 1);
        List<OrderLot> lots = List.of(
                OrderLot.of(1L, 201L, 1, EXPIRY.plusDays(3), false, null),
                OrderLot.of(1L, 101L, 1, EXPIRY, true, 3490L),
                OrderLot.of(1L, 202L, 1, EXPIRY.plusDays(4), false, null));

        assertThat(OrderLineResponse.from(order, lots)).containsExactly(
                new OrderLineResponse(true, 1, 3490L, 3490L),
                new OrderLineResponse(false, 2, 4990L, 9980L));
    }

    @Test
    void 재고를_잡기_전이면_줄이_없다() {
        Order order = Order.create("c", "k", 1L, "프레시 강남점", 15L, 2, 0);
        assertThat(OrderLineResponse.from(order, List.of())).isEmpty();
        assertThat(OrderLineResponse.from(reservedOrder(2, 0), null)).isEmpty();
    }
}
