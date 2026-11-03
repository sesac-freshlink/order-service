package com.freshlink.order.domain.payment;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.payment.toss.TossLookupResult;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OP-01 결제 결과 수동 확인·OP-02 멈춘 주문 수동 종료를 실제 MySQL에서 확인한다. 토스 클라이언트는 목 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OpsIntegrationTest {

    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.parse("2026-10-20T15:35:10+09:00");
    private static final String RECEIPT = "https://dashboard.tosspayments.com/receipt/redirection?transactionId=tviva_1";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @MockitoBean
    TossPaymentsClient toss;

    @BeforeEach
    void setUp() {
        when(toss.isConfigured()).thenReturn(true);
    }

    /** 상태를 SQL로 맞춘 주문. PAYMENT_APPROVING이면 결제 기록(APPROVING)도 넣는다 */
    private long order(String status) {
        Order o = orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 7L, "점포", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, amount = 3400, unit_price = 1700, product_name = '상품', "
                + "order_no = ? WHERE id = ?", status, "FL-20261020-" + o.getId(), o.getId());
        if ("PAYMENT_APPROVING".equals(status)) {
            jdbc.update("INSERT INTO payment (order_id, payment_key, toss_idempotency_key, amount, status, "
                    + "created_at, updated_at) VALUES (?, ?, ?, 3400, 'APPROVING', NOW(6), NOW(6))",
                    o.getId(), "pk_" + o.getId(), UUID.randomUUID().toString());
        }
        return o.getId();
    }

    private ResultActions sync(long orderId) throws Exception {
        return mockMvc.perform(post("/ops/orders/" + orderId + "/payment-sync"));
    }

    private ResultActions close(long orderId) throws Exception {
        return mockMvc.perform(post("/ops/orders/" + orderId + "/close"));
    }

    private OrderStatus statusOf(long id) {
        return orderRepository.findById(id).orElseThrow().getStatus();
    }

    private List<BaseMessage> outboxOf(long orderId, String type) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = ?", String.class, type).stream()
                .map(p -> (BaseMessage) MessageJson.fromJson(p))
                .filter(m -> (m instanceof ConfirmMessage c && c.orderId().equals(orderId))
                        || (m instanceof ReleaseMessage r && r.orderId().equals(orderId)))
                .toList();
    }

    /** 이 주문과 맞는 토스 결제 (주문번호 FL-20261020-{id}, 금액 3400) */
    private void tossSays(long orderId, String tossStatus) {
        when(toss.lookup(any())).thenReturn(
                TossLookupResult.found(tossStatus, "카드", APPROVED_AT, RECEIPT, "FL-20261020-" + orderId, 3400L));
    }

    // ---------------------------------------------------------------- OP-01

    @Test
    void 토스가_DONE이면_PAID와_CONFIRM() throws Exception {
        long id = order("PAYMENT_APPROVING");
        tossSays(id, "DONE");

        sync(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.before").value("PAYMENT_APPROVING"))
                .andExpect(jsonPath("$.after").value("PAID"))
                .andExpect(jsonPath("$.tossStatus").value("DONE"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.PAID);
        assertThat(outboxOf(id, "CONFIRM")).hasSize(1);
        Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", id);
        assertThat(payment).containsEntry("status", "DONE").containsEntry("toss_status", "DONE")
                .containsEntry("receipt_url", RECEIPT);
        verify(toss).lookup("pk_" + id);
    }

    @ParameterizedTest
    @ValueSource(strings = {"ABORTED", "EXPIRED"})
    void 토스가_최종_실패면_PAYMENT_FAILED와_RELEASE(String tossStatus) throws Exception {
        long id = order("PAYMENT_APPROVING");
        tossSays(id, tossStatus);

        sync(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.after").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.tossStatus").value(tossStatus));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.PAYMENT_FAILED);
        List<BaseMessage> releases = outboxOf(id, "RELEASE");
        assertThat(releases).hasSize(1);
        assertThat(((ReleaseMessage) releases.get(0)).reason()).isEqualTo(ReleaseReason.PAYMENT_FAILED);
        assertThat(jdbc.queryForObject("SELECT status FROM payment WHERE order_id = ?", String.class, id))
                .isEqualTo("FAILED");
    }

    @ParameterizedTest
    @ValueSource(strings = {"READY", "IN_PROGRESS", "WAITING_FOR_DEPOSIT", "CANCELED", "PARTIAL_CANCELED"})
    void 아직_불명이거나_생기면_안_되는_상태면_그대로(String tossStatus) throws Exception {
        long id = order("PAYMENT_APPROVING");
        tossSays(id, tossStatus);

        sync(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.before").value("PAYMENT_APPROVING"))
                .andExpect(jsonPath("$.after").value("PAYMENT_APPROVING"))
                .andExpect(jsonPath("$.tossStatus").value(tossStatus));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        assertThat(outboxOf(id, "CONFIRM")).isEmpty();
        assertThat(outboxOf(id, "RELEASE")).isEmpty();
    }

    @Test
    void 토스_결제의_주문번호나_금액이_다르면_그대로_둔다() throws Exception {
        long otherOrderNo = order("PAYMENT_APPROVING");
        when(toss.lookup(any())).thenReturn(
                TossLookupResult.found("DONE", "카드", APPROVED_AT, RECEIPT, "FL-20261020-999999", 3400L));
        sync(otherOrderNo).andExpect(status().isOk()).andExpect(jsonPath("$.after").value("PAYMENT_APPROVING"));

        long otherAmount = order("PAYMENT_APPROVING");
        when(toss.lookup(any())).thenReturn(
                TossLookupResult.found("ABORTED", "카드", APPROVED_AT, RECEIPT, "FL-20261020-" + otherAmount, 9999L));
        sync(otherAmount).andExpect(status().isOk()).andExpect(jsonPath("$.after").value("PAYMENT_APPROVING"));

        for (long id : List.of(otherOrderNo, otherAmount)) {
            assertThat(statusOf(id)).isEqualTo(OrderStatus.PAYMENT_APPROVING);
            assertThat(outboxOf(id, "CONFIRM")).isEmpty();
            assertThat(outboxOf(id, "RELEASE")).isEmpty();
        }
    }

    @Test
    void 조회_오류나_결제_없음만으로는_실패_처리하지_않는다() throws Exception {
        long id = order("PAYMENT_APPROVING");
        when(toss.lookup(any())).thenReturn(TossLookupResult.notFound("NOT_FOUND_PAYMENT", "없음"));

        sync(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.after").value("PAYMENT_APPROVING"))
                .andExpect(jsonPath("$.tossStatus").isEmpty());

        assertThat(statusOf(id)).isEqualTo(OrderStatus.PAYMENT_APPROVING);
    }

    @Test
    void 승인_중이_아니면_409_ORDER_NOT_SYNCABLE이고_토스를_부르지_않는다() throws Exception {
        long waiting = order("PAYMENT_WAITING");

        sync(waiting).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_SYNCABLE"));
        sync(Long.MAX_VALUE).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));

        verify(toss, never()).lookup(any());
    }

    @Test
    void 두_번_불러도_CONFIRM은_한_건() throws Exception {
        long id = order("PAYMENT_APPROVING");
        tossSays(id, "DONE");

        sync(id).andExpect(status().isOk());
        sync(id).andExpect(status().isConflict());

        assertThat(outboxOf(id, "CONFIRM")).hasSize(1);
    }

    // ---------------------------------------------------------------- OP-02

    @Test
    void 예약_중에_멈춘_주문을_닫으면_EXPIRED와_RELEASE_EXPIRED() throws Exception {
        long id = order("RESERVING");

        close(id).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.before").value("RESERVING"))
                .andExpect(jsonPath("$.after").value("EXPIRED"));

        assertThat(statusOf(id)).isEqualTo(OrderStatus.EXPIRED);
        List<BaseMessage> releases = outboxOf(id, "RELEASE");
        assertThat(releases).hasSize(1);
        ReleaseMessage release = (ReleaseMessage) releases.get(0);
        assertThat(release.reason()).isEqualTo(ReleaseReason.EXPIRED);
        assertThat(release.storeId()).isEqualTo(7L);
    }

    @Test
    void 예약_중이_아니면_409_ORDER_NOT_CLOSABLE() throws Exception {
        long waiting = order("PAYMENT_WAITING");
        long reserving = order("RESERVING");

        close(waiting).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CLOSABLE"));
        close(reserving).andExpect(status().isOk());
        close(reserving).andExpect(status().isConflict());
        close(Long.MAX_VALUE).andExpect(status().isNotFound());

        assertThat(outboxOf(reserving, "RELEASE")).hasSize(1);
        assertThat(outboxOf(waiting, "RELEASE")).isEmpty();
    }
}
