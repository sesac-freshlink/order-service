package com.freshlink.order.domain.order;

import java.time.LocalDate;
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
}
