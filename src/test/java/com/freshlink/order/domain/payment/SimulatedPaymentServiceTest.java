package com.freshlink.order.domain.payment;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.order.SalesChangedEvent;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.context.ApplicationEventPublisher;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** 시뮬레이터 결제 완료(3차 1-3): 토스 없이 SIM- 결제 + PaymentResultWriter.paid와 같은 쪽지를 남기는지 Spring 없이 확인한다 */
class SimulatedPaymentServiceTest {

    private static final OffsetDateTime NOW = OffsetDateTime.parse("2026-11-05T12:00:00+09:00");

    OrderRepository orderRepository = mock(OrderRepository.class);
    PaymentRepository paymentRepository = mock(PaymentRepository.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    ApplicationEventPublisher events = mock(ApplicationEventPublisher.class);
    SimulatedPaymentService service;

    @BeforeEach
    void setUp() {
        PaymentResultWriter writer = new PaymentResultWriter(outboxWriter, "cmd.fifo", "noti", events);
        service = new SimulatedPaymentService(orderRepository, paymentRepository, writer,
                Clock.fixed(NOW.toInstant(), ZoneId.of("Asia/Seoul")));
        when(paymentRepository.saveAndFlush(any(Payment.class))).thenAnswer(i -> i.getArgument(0));
    }

    /** 결제 대기 주문 (결제 기한 = reservedAt + 10분) */
    private Order waiting(long id, OffsetDateTime reservedAt) {
        Order order = Order.create("11111111-1111-1111-1111-111111111111", "k", 4L, "프레시링크 문래역점", 12L, 2);
        ReflectionTestUtils.setField(order, "id", id);
        order.assignOrderNo("FL-20261105-" + id);
        order.reserve("흰 우유 500ml", 1900, reservedAt);
        when(orderRepository.findByIdForUpdate(id)).thenReturn(Optional.of(order));
        return order;
    }

    @Test
    void 결제_대기면_SIM_결제로_PAID와_CONFIRM_새주문알림_매출SSE() {
        Order order = waiting(7L, NOW.minusMinutes(1));

        PaymentConfirmResponse res = service.pay(7L);

        assertThat(res.status()).isEqualTo(OrderStatus.PAID);
        assertThat(order.getPaidAt()).isEqualTo(NOW);
        ArgumentCaptor<Payment> payment = ArgumentCaptor.forClass(Payment.class);
        verify(paymentRepository).saveAndFlush(payment.capture());
        assertThat(payment.getValue().getPaymentKey()).startsWith("SIM-");
        assertThat(payment.getValue().getAmount()).isEqualTo(3800L);
        assertThat(payment.getValue().getStatus()).isEqualTo(PaymentStatus.DONE);
        assertThat(payment.getValue().getMethod()).isEqualTo("카드");
        assertThat(payment.getValue().getApprovedAt()).isEqualTo(NOW);
        verify(outboxWriter).save(eq("cmd.fifo"), any(ConfirmMessage.class));
        verify(outboxWriter).save(eq("noti"), any(NotificationMessage.class));
        verify(events).publishEvent(any(SalesChangedEvent.class));
    }

    @Test
    void 결제_기한이_지나면_409() {
        waiting(8L, NOW.minusMinutes(11));

        assertThatThrownBy(() -> service.pay(8L)).isInstanceOf(BusinessException.class);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void 픽업_기한이_지나면_409() {
        Order order = waiting(9L, NOW.minusMinutes(1));
        order.assignPickupDeadline(NOW.minusSeconds(1));

        assertThatThrownBy(() -> service.pay(9L)).isInstanceOf(BusinessException.class);
        verify(paymentRepository, never()).saveAndFlush(any());
    }

    @Test
    void 이미_SIM_결제로_PAID면_다시_불러도_그대로() {
        Order order = waiting(10L, NOW.minusMinutes(1));
        service.pay(10L);
        Payment sim = Payment.start(10L, "SIM-x", "t", 3800);
        when(paymentRepository.findByOrderId(10L)).thenReturn(Optional.of(sim));

        PaymentConfirmResponse again = service.pay(10L);

        assertThat(again.status()).isEqualTo(OrderStatus.PAID);
        verify(paymentRepository).saveAndFlush(any()); // 처음 한 번만
        assertThat(order.getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 토스로_결제된_주문은_건드리지_않는다() {
        waiting(11L, NOW.minusMinutes(1));
        service.pay(11L);
        when(paymentRepository.findByOrderId(11L)).thenReturn(Optional.of(Payment.start(11L, "tviva_1", "t", 3800)));

        assertThatThrownBy(() -> service.pay(11L)).isInstanceOf(BusinessException.class);
    }

    @Test
    void SIM_키만_시뮬레이터_결제다() {
        assertThat(SimulatedPayments.isSimulated("SIM-123")).isTrue();
        assertThat(SimulatedPayments.isSimulated("tviva_123")).isFalse();
        assertThat(SimulatedPayments.isSimulated(null)).isFalse();
    }
}
