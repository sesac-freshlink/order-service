package com.freshlink.order.domain.order;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.common.messaging.InventoryResultListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.TransientDataAccessResourceException;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.ArgumentMatchers.anyIterable;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.reset;

/**
 * order DLQ 재처리 멱등 (멘토링 후 할 일 "order 쪽 DLQ 재처리는 공통 절차를 쓰고, 다시 넣어도 멱등인지 확인한다").
 * 공통 절차(infra README 4-16)는 inventory-results DLQ의 쪽지를 같은 본문 그대로 원래 큐로 되돌린다.
 * 그래서 리스너에 같은 JSON 본문을 다시 넣는 것으로 재처리를 흉내 낸다. SQS는 목이고 리스너 컨테이너는 켜지 않는다
 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo",
        "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@Import(TestcontainersConfiguration.class)
class DlqRedriveIntegrationTest {

    private static final OffsetDateTime EXPIRY = OffsetDateTime.of(2026, 10, 25, 23, 59, 59, 0, ZoneOffset.ofHours(9));

    @Autowired
    InventoryResultListener listener;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @MockitoSpyBean
    OrderLotRepository orderLotRepository;

    /** 수량 3, 점포 1, 상품 15인 RESERVING 주문 */
    private Order newOrder() {
        return orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 1L, "프레시 강남점", 15L, 3));
    }

    private Order reload(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private String reservedBody(Order order) {
        return MessageJson.toJson(ReservedMessage.of(order.getId(), 1L, 15L, "매운 컵라면 115g", 3, 1700L,
                List.of(new ReservedLot(101L, 2, EXPIRY), new ReservedLot(102L, 1, EXPIRY.plusDays(1)))));
    }

    private int lotCount(Order order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_lot WHERE order_id = ?", Integer.class, order.getId());
    }

    private int processedCount(String body) {
        BaseMessage message = MessageJson.fromJson(body);
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", Integer.class,
                message.messageId());
    }

    private int outboxCount(Order order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE payload LIKE ?", Integer.class,
                "%\"orderId\":" + order.getId() + ",%");
    }

    @Test
    void 세_번_실패해_DLQ로_간_쪽지를_되돌리면_한_번만_반영된다() {
        Order order = newOrder();
        String body = reservedBody(order);
        // DB 일시 장애로 3번(maxReceiveCount) 실패 → DLQ
        doThrow(new TransientDataAccessResourceException("DB 일시 장애")).when(orderLotRepository).saveAll(anyIterable());
        for (int attempt = 0; attempt < 3; attempt++) {
            assertThatThrownBy(() -> listener.onResult(body)).isInstanceOf(TransientDataAccessResourceException.class);
        }
        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(processedCount(body)).isZero();
        assertThat(lotCount(order)).isZero();

        // 원인을 고친 뒤 DLQ 재처리: 같은 본문이 다시 온다
        reset(orderLotRepository);
        listener.onResult(body);

        Order saved = reload(order);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(saved.getAmount()).isEqualTo(5100L);
        assertThat(lotCount(order)).isEqualTo(2);
        assertThat(processedCount(body)).isEqualTo(1);
    }

    @Test
    void 이미_처리한_쪽지가_재처리에_섞여_다시_와도_아무것도_바뀌지_않는다() {
        Order done = newOrder();
        String doneBody = reservedBody(done);
        listener.onResult(doneBody);
        OffsetDateTime expiresAt = reload(done).getPaymentExpiresAt();

        Order pending = newOrder();
        String failedBody = MessageJson.toJson(
                ReserveFailedMessage.of(pending.getId(), 1L, 15L, ReserveFailReason.OUT_OF_STOCK, 1));

        // DLQ에 처리된 쪽지와 아직 처리 안 된 쪽지가 함께 있다가 되돌려진다 (두 번 되돌려도 같다)
        for (int redrive = 0; redrive < 2; redrive++) {
            listener.onResult(doneBody);
            listener.onResult(failedBody);
        }

        Order saved = reload(done);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(saved.getPaymentExpiresAt()).isEqualTo(expiresAt);
        assertThat(lotCount(done)).isEqualTo(2);
        assertThat(processedCount(doneBody)).isEqualTo(1);
        assertThat(reload(pending).getStatus()).isEqualTo(OrderStatus.OUT_OF_STOCK);
        assertThat(reload(pending).getAvailableQuantity()).isEqualTo(1);
        assertThat(processedCount(failedBody)).isEqualTo(1);
    }

    @Test
    void 수동_종료한_주문의_RESERVED를_여러_번_되돌려도_RELEASE는_한_번() {
        Order order = newOrder();
        String body = reservedBody(order);
        jdbc.update("UPDATE orders SET status = 'EXPIRED', failure_reason = 'EXPIRED', closed_at = NOW(6) WHERE id = ?",
                order.getId());

        listener.onResult(body);
        listener.onResult(body);
        listener.onResult(body);

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(outboxCount(order)).isEqualTo(1);
        assertThat(processedCount(body)).isEqualTo(1);
    }

    @Test
    void 없는_주문의_쪽지는_되돌려도_처리_기록을_남기지_않고_다시_실패한다() {
        String body = MessageJson.toJson(ReservedMessage.of(Long.MAX_VALUE, 1L, 15L, "매운 컵라면 115g", 3, 1700L,
                List.of(new ReservedLot(101L, 3, EXPIRY))));

        assertThatThrownBy(() -> listener.onResult(body)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> listener.onResult(body)).isInstanceOf(IllegalStateException.class);

        assertThat(processedCount(body)).isZero();
    }
}
