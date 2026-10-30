package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static com.freshlink.order.domain.order.OrderStatus.*;
import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 허용되는 주문 상태 전이 표 (MVP 명세 2장, 추가 명세 0.3) */
class OrderStatusTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 30, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    /** 명세 표대로 허용되는 전이. 여기 없는 조합은 모두 막혀야 한다 */
    private static final Map<OrderStatus, Set<OrderStatus>> EXPECTED = Map.of(
            RESERVING, EnumSet.of(PAYMENT_WAITING, OUT_OF_STOCK, EXPIRED),
            PAYMENT_WAITING, EnumSet.of(PAYMENT_APPROVING, EXPIRED, CANCELED),
            PAYMENT_APPROVING, EnumSet.of(PAID, PAYMENT_FAILED),
            PAID, EnumSet.of(PICKED_UP, PICKUP_EXPIRED));

    @Test
    void 명세_표에_있는_전이만_허용한다() {
        for (OrderStatus from : OrderStatus.values()) {
            Set<OrderStatus> allowed = EXPECTED.getOrDefault(from, EnumSet.noneOf(OrderStatus.class));
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(from.canChangeTo(to))
                        .as("%s -> %s", from, to)
                        .isEqualTo(allowed.contains(to));
            }
        }
    }

    @Test
    void 끝난_상태는_어디로도_가지_않는다() {
        for (OrderStatus done : EnumSet.of(OUT_OF_STOCK, PAYMENT_FAILED, EXPIRED, PICKED_UP, PICKUP_EXPIRED, CANCELED)) {
            for (OrderStatus to : OrderStatus.values()) {
                assertThat(done.canChangeTo(to)).as("%s -> %s", done, to).isFalse();
            }
        }
    }

    @Test
    void 같은_상태로_다시_바꾸는_것도_막는다() {
        for (OrderStatus status : OrderStatus.values()) {
            assertThat(status.canChangeTo(status)).as(status.name()).isFalse();
        }
    }

    @Test
    void 픽업_완료한_주문은_기한이_지나도_PICKUP_EXPIRED로_가지_않는다() {
        Order order = paidOrder(NOW.plusHours(1));
        order.pickUp(NOW);

        assertThatThrownBy(() -> order.expirePickup(NOW.plusHours(2)))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(PICKED_UP);
    }

    @Test
    void 막힌_전이는_상태를_바꾸지_않고_예외를_낸다() {
        Order order = paidOrder(NOW.plusHours(1));

        assertThatThrownBy(() -> order.closeStuck(NOW))
                .isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(PAID);
    }

    private static Order paidOrder(OffsetDateTime pickupDeadline) {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 1);
        order.reserve("매운 컵라면 115g", 1500L, NOW.minusMinutes(5));
        order.assignPickupDeadline(pickupDeadline);
        order.startApproval(NOW.minusMinutes(4));
        order.markPaid(NOW.minusMinutes(4));
        ReflectionTestUtils.setField(order, "id", 1L);
        return order;
    }
}
