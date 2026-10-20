package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.outbox.OutboxWriter;
import java.util.Optional;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.test.util.ReflectionTestUtils;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import java.time.OffsetDateTime;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;

/** Spring 없이 동시 요청 충돌 경로와 쪽지 내용을 확인한다 */
class OrderServiceTest {

    private static final String QUEUE = "test-inventory-commands.fifo";
    private static final String CUSTOMER = "11111111-1111-1111-1111-111111111111";
    private static final String KEY = "22222222-2222-2222-2222-222222222222";

    OrderRepository orderRepository = mock(OrderRepository.class);
    OutboxWriter outboxWriter = mock(OutboxWriter.class);
    OrderService service;

    @BeforeEach
    void setUp() {
        TransactionTemplate tx = new TransactionTemplate(mock(PlatformTransactionManager.class));
        service = new OrderService(orderRepository, outboxWriter, tx, QUEUE);
    }

    private CreateOrderRequest request(int quantity) {
        return new CreateOrderRequest(1L, "프레시마트 강남점", 10L, quantity);
    }

    private Order saved(long id) {
        Order order = Order.create(CUSTOMER, KEY, 1L, "프레시마트 강남점", 10L, 2);
        ReflectionTestUtils.setField(order, "id", id);
        ReflectionTestUtils.setField(order, "createdAt", OffsetDateTime.parse("2026-10-20T10:00:00+09:00"));
        return order;
    }

    @Test
    void 새_주문이면_저장하고_RESERVE_쪽지를_outbox에_넣는다() {
        when(orderRepository.findByCustomerIdAndRequestId(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(orderRepository.saveAndFlush(any(Order.class))).thenReturn(saved(123L));

        CreateOrderResult result = service.create(CUSTOMER, KEY, request(2));

        assertThat(result.created()).isTrue();
        assertThat(result.order().getOrderNo()).isEqualTo("FL-20261020-000123");
        ArgumentCaptor<ReserveMessage> captor = ArgumentCaptor.forClass(ReserveMessage.class);
        verify(outboxWriter).save(org.mockito.ArgumentMatchers.eq(QUEUE), captor.capture());
        ReserveMessage msg = captor.getValue();
        assertThat(msg.orderId()).isEqualTo(123L);
        assertThat(msg.storeId()).isEqualTo(1L);
        assertThat(msg.productId()).isEqualTo(10L);
        assertThat(msg.quantity()).isEqualTo(2);
    }

    @Test
    void 같은_키_같은_내용_재요청은_새로_만들지_않는다() {
        when(orderRepository.findByCustomerIdAndRequestId(CUSTOMER, KEY)).thenReturn(Optional.of(saved(5L)));

        CreateOrderResult result = service.create(CUSTOMER, KEY, request(2));

        assertThat(result.created()).isFalse();
        verify(orderRepository, never()).saveAndFlush(any());
        verify(outboxWriter, never()).save(any(), any());
    }

    @Test
    void 같은_키_다른_내용이면_IDEMPOTENCY_KEY_REUSED() {
        when(orderRepository.findByCustomerIdAndRequestId(CUSTOMER, KEY)).thenReturn(Optional.of(saved(5L)));

        assertThatThrownBy(() -> service.create(CUSTOMER, KEY, request(3)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 동시_요청이_유니크_키에_걸리면_먼저_만든_주문으로_응답한다() {
        when(orderRepository.findByCustomerIdAndRequestId(CUSTOMER, KEY))
                .thenReturn(Optional.empty(), Optional.of(saved(5L)));
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(new DataIntegrityViolationException("dup"));

        CreateOrderResult result = service.create(CUSTOMER, KEY, request(2));

        assertThat(result.created()).isFalse();
        assertThat(result.order().getId()).isEqualTo(5L);
    }

    @Test
    void 충돌_뒤_내용이_다르면_IDEMPOTENCY_KEY_REUSED() {
        when(orderRepository.findByCustomerIdAndRequestId(CUSTOMER, KEY))
                .thenReturn(Optional.empty(), Optional.of(saved(5L)));
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(new DataIntegrityViolationException("dup"));

        assertThatThrownBy(() -> service.create(CUSTOMER, KEY, request(4)))
                .isInstanceOfSatisfying(BusinessException.class,
                        e -> assertThat(e.code()).isEqualTo("IDEMPOTENCY_KEY_REUSED"));
    }

    @Test
    void 충돌했는데_주문이_없으면_원래_예외를_다시_던진다() {
        DataIntegrityViolationException dup = new DataIntegrityViolationException("other constraint");
        when(orderRepository.findByCustomerIdAndRequestId(CUSTOMER, KEY)).thenReturn(Optional.empty());
        when(orderRepository.saveAndFlush(any(Order.class))).thenThrow(dup);

        assertThatThrownBy(() -> service.create(CUSTOMER, KEY, request(2))).isSameAs(dup);
    }
}
