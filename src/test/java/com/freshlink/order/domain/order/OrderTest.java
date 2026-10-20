package com.freshlink.order.domain.order;

import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class OrderTest {

    @Test
    void 주문번호는_6자리로_채운다() {
        assertThat(Order.formatOrderNo(123L, LocalDate.of(2026, 10, 20))).isEqualTo("FL-20261020-000123");
    }

    @Test
    void 주문번호_id가_6자리를_넘으면_자릿수가_늘어난다() {
        assertThat(Order.formatOrderNo(1_000_000L, LocalDate.of(2026, 10, 20))).isEqualTo("FL-20261020-1000000");
    }

    @Test
    void 주문번호는_두번_정할_수_없다() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);
        order.assignOrderNo("FL-20261020-000001");

        assertThatThrownBy(() -> order.assignOrderNo("FL-20261020-000002"))
                .isInstanceOf(IllegalStateException.class);
    }

    @Test
    void 점포_상품_수량이_같으면_같은_내용() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);

        assertThat(order.hasSameContent(1L, 10L, 2)).isTrue();
        assertThat(order.hasSameContent(2L, 10L, 2)).isFalse();
        assertThat(order.hasSameContent(1L, 11L, 2)).isFalse();
        assertThat(order.hasSameContent(1L, 10L, 3)).isFalse();
    }

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 20, 15, 31, 55, 0, ZoneOffset.ofHours(9));

    @Test
    void 예약되면_결제_대기로_바뀌고_금액과_결제_마감이_정해진다() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);

        order.reserve("매운 컵라면 115g", 1700L, NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(order.getProductName()).isEqualTo("매운 컵라면 115g");
        assertThat(order.getUnitPrice()).isEqualTo(1700L);
        assertThat(order.getAmount()).isEqualTo(3400L);
        assertThat(order.getPaymentWaitingAt()).isEqualTo(NOW);
        assertThat(order.getPaymentExpiresAt()).isEqualTo(NOW.plusMinutes(10));
        assertThat(order.getFailureReason()).isNull();
    }

    @Test
    void 예약이_거절되면_재고_부족으로_끝난다() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);

        order.rejectReservation(OrderFailureReason.PRODUCT_NOT_FOUND, 0, NOW);

        assertThat(order.getStatus()).isEqualTo(OrderStatus.OUT_OF_STOCK);
        assertThat(order.getFailureReason()).isEqualTo(OrderFailureReason.PRODUCT_NOT_FOUND);
        assertThat(order.getAvailableQuantity()).isZero();
        assertThat(order.getClosedAt()).isEqualTo(NOW);
        assertThat(order.getAmount()).isNull();
    }

    @Test
    void 예약_중이_아니면_예약_결과를_반영할_수_없다() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);
        order.reserve("상품", 1000L, NOW);

        assertThatThrownBy(() -> order.reserve("상품", 1000L, NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.rejectReservation(OrderFailureReason.OUT_OF_STOCK, 1, NOW))
                .isInstanceOf(IllegalStateException.class);
    }
}
