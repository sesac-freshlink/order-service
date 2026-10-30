package com.freshlink.order.domain.order;

import com.freshlink.order.domain.refund.RefundStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 주문의 환불 상태 규칙 (추가 명세 0.3·0.4, A5·A9 픽업·환불 경합) */
class OrderRefundTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 30, 10, 0, 0, 0, ZoneOffset.ofHours(9));

    @Test
    void 처음_취소는_결제_완료_환불_NONE_기한_이내에서만_시작한다() {
        assertThat(paidOrder(NOW.plusHours(1)).canStartRefund(NOW)).isTrue();
        assertThat(paidOrder(NOW.minusSeconds(1)).canStartRefund(NOW)).isFalse();

        Order picked = paidOrder(NOW.plusHours(1));
        picked.pickUp(NOW);
        assertThat(picked.canStartRefund(NOW)).isFalse();
    }

    @Test
    void 실패한_환불은_기한이_지나도_재신청할_수_있고_처리_중이면_못_한다() {
        Order order = paidOrder(NOW.plusHours(1));
        order.startRefund(NOW);
        assertThat(order.canStartRefund(NOW)).isFalse();

        order.failRefund();
        assertThat(order.canStartRefund(NOW.plusDays(1))).isTrue();
        order.startRefund(NOW.plusDays(1));
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        assertThat(order.getCancelRequestedAt()).isEqualTo(NOW.plusDays(1));
    }

    @Test
    void 환불이_시작되면_픽업도_기한_경과도_막힌다() {
        Order order = paidOrder(NOW.plusHours(1));
        order.startRefund(NOW);
        order.checkRefund();

        assertThatThrownBy(() -> order.pickUp(NOW)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> order.expirePickup(NOW.plusHours(2))).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 환불_완료면_REFUNDED로_닫힌다() {
        Order order = paidOrder(NOW.plusHours(1));
        order.startRefund(NOW);
        order.completeRefund(NOW.plusSeconds(3), NOW.plusSeconds(4));

        assertThat(order.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(order.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(order.getRefundedAt()).isEqualTo(NOW.plusSeconds(3));
        assertThat(order.getClosedAt()).isEqualTo(NOW.plusSeconds(4));
    }

    @Test
    void 환불_요청_없이_완료로_바꿀_수_없다() {
        Order order = paidOrder(NOW.plusHours(1));

        assertThatThrownBy(() -> order.completeRefund(NOW, NOW)).isInstanceOf(IllegalStateException.class);
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
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
