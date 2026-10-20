package com.freshlink.order.domain.order;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.result.ReserveFailReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.common.messaging.InventoryResultListener;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.function.Function;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.EnumSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** inventory-results 쪽지(RESERVED·RESERVE_FAILED) 반영을 실제 MySQL에서 확인한다. SQS는 목이고 리스너 컨테이너는 켜지 않는다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo",
        "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false"})
@Import(TestcontainersConfiguration.class)
class ReservationResultIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 20, 15, 31, 55, 0, KST);
    private static final OffsetDateTime EXPIRY = OffsetDateTime.of(2026, 10, 25, 23, 59, 59, 0, KST);

    @Autowired
    ReservationResultHandler handler;
    @Autowired
    InventoryResultListener listener;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.from(NOW), ZoneId.of("Asia/Seoul"));
    }

    /** 수량 3, 점포 1, 상품 15인 RESERVING 주문 */
    private Order newOrder() {
        return orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 1L, "프레시 강남점", 15L, 3));
    }

    private Order reload(Order order) {
        return orderRepository.findById(order.getId()).orElseThrow();
    }

    private ReservedMessage reserved(Order order, List<ReservedLot> lots) {
        return ReservedMessage.of(order.getId(), 1L, 15L, "매운 컵라면 115g", 3, 1700L, lots);
    }

    private List<ReservedLot> twoLots() {
        return List.of(new ReservedLot(101L, 2, EXPIRY), new ReservedLot(102L, 1, EXPIRY.plusDays(1)));
    }

    private int lotCount(Order order) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM order_lot WHERE order_id = ?", Integer.class, order.getId());
    }

    private boolean processed(BaseMessage message) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM processed_message WHERE message_id = ?", Integer.class,
                message.messageId()) == 1;
    }

    @Test
    void RESERVED면_결제_대기로_바뀌고_금액_상품명_lot이_저장된다() {
        Order order = newOrder();

        handler.handle(reserved(order, twoLots()));

        Order saved = reload(order);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(saved.getProductName()).isEqualTo("매운 컵라면 115g");
        assertThat(saved.getUnitPrice()).isEqualTo(1700L);
        assertThat(saved.getAmount()).isEqualTo(5100L);
        assertThat(saved.getPaymentWaitingAt()).isEqualTo(NOW);
        assertThat(saved.getPaymentExpiresAt()).isEqualTo(NOW.plusMinutes(10));

        List<Map<String, Object>> lots = jdbc.queryForList(
                "SELECT lot_id, quantity, DATE_FORMAT(expiry_at, '%Y-%m-%d %H:%i:%s') AS expiry "
                        + "FROM order_lot WHERE order_id = ? ORDER BY lot_id", order.getId());
        assertThat(lots).hasSize(2);
        assertThat(lots.get(0)).containsEntry("quantity", 2).containsEntry("expiry", "2026-10-25 23:59:59");
        assertThat(((Number) lots.get(1).get("lot_id")).longValue()).isEqualTo(102L);
    }

    @Test
    void 같은_RESERVED를_두_번_받아도_한_번만_반영한다() {
        Order order = newOrder();
        ReservedMessage message = reserved(order, twoLots());

        handler.handle(message);
        handler.handle(message);

        assertThat(lotCount(order)).isEqualTo(2);
        assertThat(reload(order).getAmount()).isEqualTo(5100L);
    }

    @ParameterizedTest
    @EnumSource(ReserveFailReason.class)
    void RESERVE_FAILED면_재고_부족으로_끝나고_사유가_남는다(ReserveFailReason reason) {
        Order order = newOrder();

        handler.handle(ReserveFailedMessage.of(order.getId(), 1L, 15L, reason, 1));

        Order saved = reload(order);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.OUT_OF_STOCK);
        assertThat(saved.getFailureReason()).isEqualTo(OrderFailureReason.valueOf(reason.name()));
        assertThat(saved.getAvailableQuantity()).isEqualTo(1);
        assertThat(saved.getClosedAt()).isEqualTo(NOW);
        assertThat(saved.getAmount()).isNull();
    }

    @Test
    void 예약_중이_아닌_주문의_결과는_무시하고_처리_기록만_남긴다() {
        Order order = newOrder();
        handler.handle(reserved(order, twoLots()));

        ReservedMessage lateReserved = reserved(order, List.of(new ReservedLot(103L, 3, EXPIRY)));
        ReserveFailedMessage lateFailed = ReserveFailedMessage.of(order.getId(), 1L, 15L, ReserveFailReason.OUT_OF_STOCK, 0);
        handler.handle(lateReserved);
        handler.handle(lateFailed);

        Order saved = reload(order);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(saved.getFailureReason()).isNull();
        assertThat(lotCount(order)).isEqualTo(2);
        assertThat(processed(lateReserved)).isTrue();
        assertThat(processed(lateFailed)).isTrue();
    }

    @Test
    void lot_합계가_수량과_다르면_반영하지_않고_예외() {
        assertRejected(order -> reserved(order, List.of(new ReservedLot(101L, 2, EXPIRY))));
    }

    @Test
    void 점포가_다르면_반영하지_않고_예외() {
        assertRejected(order -> ReservedMessage.of(order.getId(), 2L, 15L, "상품", 3, 1700L, twoLots()));
    }

    @Test
    void 상품이_다르면_반영하지_않고_예외() {
        assertRejected(order -> ReservedMessage.of(order.getId(), 1L, 16L, "상품", 3, 1700L, twoLots()));
    }

    @Test
    void 수량이_다르면_반영하지_않고_예외() {
        assertRejected(order -> ReservedMessage.of(order.getId(), 1L, 15L, "상품", 2,
                1700L, List.of(new ReservedLot(101L, 2, EXPIRY))));
    }

    @Test
    void lot이_없으면_반영하지_않고_예외() {
        assertRejected(order -> reserved(order, List.of()));
    }

    @Test
    void lot_수량이_0이면_반영하지_않고_예외() {
        assertRejected(order -> reserved(order,
                List.of(new ReservedLot(101L, 3, EXPIRY), new ReservedLot(102L, 0, EXPIRY))));
    }

    @Test
    void 상품명이_비었거나_단가가_음수면_반영하지_않고_예외() {
        assertRejected(order -> ReservedMessage.of(order.getId(), 1L, 15L, " ", 3, 1700L, twoLots()));
        assertRejected(order -> ReservedMessage.of(order.getId(), 1L, 15L, "상품", 3, -1L, twoLots()));
    }

    @Test
    void 없는_주문의_결과는_예외() {
        ReservedMessage message = ReservedMessage.of(Long.MAX_VALUE, 1L, 15L, "상품", 3, 1700L, twoLots());
        ReserveFailedMessage failed = ReserveFailedMessage.of(Long.MAX_VALUE, 1L, 15L, ReserveFailReason.OUT_OF_STOCK, 0);

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(IllegalStateException.class);
        assertThatThrownBy(() -> handler.handle(failed)).isInstanceOf(IllegalStateException.class);
        assertThat(processed(message)).isFalse();
        assertThat(processed(failed)).isFalse();
    }

    @Test
    void 리스너는_JSON_본문을_읽어_반영한다_날짜만_온_유통기한은_그날_23시59분59초() {
        Order order = newOrder();
        String body = "{\"type\":\"RESERVED\",\"messageId\":\"" + UUID.randomUUID() + "\","
                + "\"createdAt\":\"2026-10-20T15:31:50+09:00\",\"orderId\":" + order.getId() + ","
                + "\"storeId\":1,\"productId\":15,\"productName\":\"매운 컵라면 115g\",\"quantity\":3,"
                + "\"unitPrice\":1700,\"lots\":[{\"lotId\":101,\"quantity\":3,\"expiryDate\":\"2026-10-25\"}]}";

        listener.onResult(body);

        assertThat(reload(order).getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(jdbc.queryForObject(
                "SELECT DATE_FORMAT(expiry_at, '%Y-%m-%d %H:%i:%s') FROM order_lot WHERE order_id = ?",
                String.class, order.getId())).isEqualTo("2026-10-25 23:59:59");
    }

    @Test
    void 리스너는_다른_종류의_쪽지를_무시한다() {
        ConfirmMessage confirm = ConfirmMessage.of(1L, 1L);

        listener.onResult(MessageJson.toJson(confirm));

        assertThat(processed(confirm)).isFalse();
    }

    /** 반영되지 않았고(상태·lot 그대로) 처리 기록도 롤백됐는지 본다. 예외가 나야 SQS가 다시 보내고 3회 뒤 DLQ로 간다 */
    private void assertRejected(Function<Order, ReservedMessage> messageFor) {
        Order order = newOrder();
        ReservedMessage message = messageFor.apply(order);

        assertThatThrownBy(() -> handler.handle(message)).isInstanceOf(IllegalStateException.class);

        Order saved = reload(order);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(saved.getAmount()).isNull();
        assertThat(lotCount(order)).isZero();
        assertThat(processed(message)).isFalse();
    }
}
