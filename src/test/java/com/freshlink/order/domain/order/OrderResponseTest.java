package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 시각은 Jackson 설정과 상관없이 DTO에서 +09:00으로 바뀐다 */
class OrderResponseTest {

    @Test
    void UTC로_읽힌_시각도_KST로_바꾼다() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);
        ReflectionTestUtils.setField(order, "createdAt", OffsetDateTime.parse("2026-10-20T06:31:55Z"));
        order.reserve("상품", 1000L, OffsetDateTime.parse("2026-10-20T06:32:00Z"));

        OrderResponse res = OrderResponse.from(order);

        assertThat(res.createdAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T15:31:55+09:00"));
        assertThat(res.createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(res.paymentExpiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(res.paidAt()).isNull();
    }
}
