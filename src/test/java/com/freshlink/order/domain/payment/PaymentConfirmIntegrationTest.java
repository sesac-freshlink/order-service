package com.freshlink.order.domain.payment;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderExpiryService;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.order.ReservationResultHandler;
import com.freshlink.order.domain.payment.toss.TossConfirmResult;
import com.freshlink.order.domain.payment.toss.TossOutcome;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicReference;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.mockito.ArgumentCaptor;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.doAnswer;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OR-03 결제 승인 전체 흐름을 실제 MySQL에서 확인한다. 토스 클라이언트는 목이라 실제 토스에 접속하지 않는다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentConfirmIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 20, 15, 31, 55, 0, KST);
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 10, 20, 15, 35, 10, 0, KST);
    private static final String RECEIPT = "https://dashboard.tosspayments.com/receipt/redirection?transactionId=tviva_1";

    @Autowired
    MockMvc mockMvc;
    /** 스파이지만 따로 정하지 않으면 실제 저장소 그대로다. 만료와 승인 순서를 맞추는 테스트에서만 끼어든다 */
    @MockitoSpyBean
    OrderRepository orderRepository;
    @Autowired
    ReservationResultHandler reservationResultHandler;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderExpiryService expiryService;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @MockitoBean
    TossPaymentsClient toss;
    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.from(NOW), ZoneId.of("Asia/Seoul"));
    }

    String customer;
    Order order;

    @BeforeEach
    void setUp() {
        when(toss.isConfigured()).thenReturn(true);
        customer = UUID.randomUUID().toString();
        order = waitingOrder(customer);
    }

    /** RESERVED까지 받은 결제 대기 주문 (수량 2 × 1700 = 3400, 마감 NOW + 10분) */
    private Order waitingOrder(String customerId) {
        Order o = orderRepository.saveAndFlush(Order.create(customerId, UUID.randomUUID().toString(),
                1L, "프레시 강남점", 15L, 2));
        jdbc.update("UPDATE orders SET order_no = ? WHERE id = ?", "FL-20261020-" + o.getId(), o.getId());
        reservationResultHandler.handle(ReservedMessage.of(o.getId(), 1L, 15L, "매운 컵라면 115g", 2, 1700L,
                List.of(new ReservedLot(101L, 2, OffsetDateTime.parse("2026-10-25T23:59:59+09:00")))));
        return orderRepository.findById(o.getId()).orElseThrow();
    }

    private String body(String paymentKey, String orderNo, long amount) {
        return "{\"paymentKey\":\"" + paymentKey + "\",\"orderNo\":\"" + orderNo + "\",\"amount\":" + amount + "}";
    }

    private ResultActions confirm(String customerId, long orderId, String body) throws Exception {
        return mockMvc.perform(post("/orders/" + orderId + "/payment/confirm")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-User-Id", customerId).header("X-User-Role", "CUSTOMER"));
    }

    private ResultActions confirm(String paymentKey) throws Exception {
        return confirm(customer, order.getId(), body(paymentKey, order.getOrderNo(), 3400));
    }

    private OrderStatus statusOf(Order o) {
        return orderRepository.findById(o.getId()).orElseThrow().getStatus();
    }

    private Map<String, Object> paymentRow(Order o) {
        return jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", o.getId());
    }

    /** 이 주문의 outbox 쪽지 (종류별). payload는 JSON으로 읽어 orderId를 비교한다 */
    private List<String> outboxPayloads(String type) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = ?", String.class, type).stream()
                .filter(p -> orderIdOf(MessageJson.fromJson(p)).equals(order.getId()))
                .toList();
    }

    private static Long orderIdOf(BaseMessage m) {
        if (m instanceof ConfirmMessage c) return c.orderId();
        if (m instanceof ReleaseMessage r) return r.orderId();
        return -1L;
    }

    private void tossReturns(TossConfirmResult result) {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenReturn(result);
    }

    @Test
    void 승인_성공이면_PAID와_CONFIRM_쪽지_결제_기록이_남는다() throws Exception {
        tossReturns(TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT));

        confirm("pk_ok")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.getId()))
                .andExpect(jsonPath("$.orderNo").value(order.getOrderNo()))
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.storeName").value("프레시 강남점"))
                .andExpect(jsonPath("$.productName").value("매운 컵라면 115g"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.amount").value(3400))
                .andExpect(jsonPath("$.paidAt").value("2026-10-20T15:35:10+09:00"));

        ArgumentCaptor<String> tossKey = ArgumentCaptor.forClass(String.class);
        verify(toss).confirm(eq("pk_ok"), eq(order.getOrderNo()), eq(3400L), tossKey.capture());
        Map<String, Object> payment = paymentRow(order);
        assertThat(payment.get("status")).isEqualTo("DONE");
        assertThat(payment.get("payment_key")).isEqualTo("pk_ok");
        assertThat(payment.get("toss_idempotency_key")).isEqualTo(tossKey.getValue());
        assertThat(tossKey.getValue()).isNotEqualTo(order.getRequestId());
        assertThat(payment.get("toss_status")).isEqualTo("DONE");
        assertThat(payment.get("method")).isEqualTo("카드");
        assertThat(payment.get("receipt_url")).isEqualTo(RECEIPT);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);

        List<String> confirms = outboxPayloads("CONFIRM");
        assertThat(confirms).hasSize(1);
        ConfirmMessage msg = MessageJson.fromJson(confirms.get(0), ConfirmMessage.class);
        assertThat(msg.storeId()).isEqualTo(1L);
        assertThat(outboxPayloads("RELEASE")).isEmpty();
    }

    @Test
    void 승인_뒤_주문_조회에_결제_내역과_서버_시각이_나온다() throws Exception {
        String orderPath = "/orders/" + order.getId();
        mockMvc.perform(get(orderPath).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_WAITING"))
                .andExpect(jsonPath("$.payment").isEmpty())
                .andExpect(jsonPath("$.serverTime").value("2026-10-20T15:31:55+09:00"));

        tossReturns(TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT));
        confirm("pk_detail").andExpect(status().isOk());

        mockMvc.perform(get(orderPath).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.payment.method").value("카드"))
                .andExpect(jsonPath("$.payment.approvedAt").value("2026-10-20T15:35:10+09:00"))
                .andExpect(jsonPath("$.payment.status").value("DONE"))
                .andExpect(jsonPath("$.payment.receiptUrl").value(RECEIPT))
                .andExpect(jsonPath("$.serverTime").value("2026-10-20T15:31:55+09:00"));
    }

    @Test
    void 영수증이_없는_승인도_PAID이고_영수증_주소만_null() throws Exception {
        tossReturns(TossConfirmResult.approved("DONE", "카드", APPROVED_AT, null));

        confirm("pk_no_receipt").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        assertThat(paymentRow(order).get("receipt_url")).isNull();
        mockMvc.perform(get("/orders/" + order.getId()).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.payment.status").value("DONE"))
                .andExpect(jsonPath("$.payment.receiptUrl").isEmpty());
    }

    @Test
    void 결제_시간이_남아도_픽업_기한이_지났으면_토스를_부르지_않고_409() throws Exception {
        jdbc.update("UPDATE orders SET pickup_deadline = ? WHERE id = ?", NOW.minusSeconds(1).toLocalDateTime(), order.getId());

        confirm("pk_deadline").andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PICKUP_DEADLINE_PASSED"));

        verify(toss, never()).confirm(anyString(), anyString(), anyLong(), anyString());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYMENT_WAITING);
    }

    @Test
    void 확정_실패면_PAYMENT_FAILED와_RELEASE_쪽지() throws Exception {
        tossReturns(TossConfirmResult.error(TossOutcome.DEFINITE_FAILURE, "REJECT_CARD_PAYMENT", "한도 초과"));

        confirm("pk_fail")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_FAILED"))
                .andExpect(jsonPath("$.paidAt").isEmpty());

        Order saved = orderRepository.findById(order.getId()).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(saved.getFailureReason().name()).isEqualTo("PAYMENT_FAILED");
        assertThat(saved.getClosedAt()).isNotNull();
        assertThat(paymentRow(order)).containsEntry("status", "FAILED").containsEntry("error_code", "REJECT_CARD_PAYMENT");
        List<String> releases = outboxPayloads("RELEASE");
        assertThat(releases).hasSize(1);
        assertThat(MessageJson.fromJson(releases.get(0), ReleaseMessage.class).reason()).isEqualTo(ReleaseReason.PAYMENT_FAILED);
        assertThat(outboxPayloads("CONFIRM")).isEmpty();
    }

    @Test
    void 결과_불명_이미_처리_연동_오류면_승인_중으로_남고_쪽지가_없다() throws Exception {
        for (TossOutcome outcome : List.of(TossOutcome.UNKNOWN, TossOutcome.ALREADY_PROCESSED, TossOutcome.CONFIG_ERROR)) {
            order = waitingOrder(customer);
            tossReturns(TossConfirmResult.error(outcome, "SOME_CODE", "메시지"));

            confirm("pk_" + outcome)
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAYMENT_APPROVING"));

            assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYMENT_APPROVING);
            assertThat(paymentRow(order)).containsEntry("status", "APPROVING").containsEntry("error_code", "SOME_CODE");
            assertThat(outboxPayloads("CONFIRM")).isEmpty();
            assertThat(outboxPayloads("RELEASE")).isEmpty();
        }
    }

    @Test
    void 같은_paymentKey로_다시_오면_토스를_다시_부르지_않고_지금_상태를_준다() throws Exception {
        tossReturns(TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT));
        confirm("pk_same").andExpect(status().isOk());

        confirm("pk_same")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.paidAt").value("2026-10-20T15:35:10+09:00"));

        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        assertThat(outboxPayloads("CONFIRM")).hasSize(1);
    }

    @Test
    void 같은_주문에_승인_요청이_동시에_와도_토스는_한_번만_부르고_결제는_한_건() throws Exception {
        // 첫 요청이 토스 응답을 기다리는(PAYMENT_APPROVING 커밋 뒤) 동안 두 번째 요청을 보낸다
        CountDownLatch inToss = new CountDownLatch(1);
        CountDownLatch releaseToss = new CountDownLatch(1);
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            inToss.countDown();
            releaseToss.await(30, TimeUnit.SECONDS);
            return TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT);
        });
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<Integer> first = pool.submit(() -> confirm("pk_dup").andReturn().getResponse().getStatus());
            assertThat(inToss.await(30, TimeUnit.SECONDS)).isTrue();

            confirm("pk_dup")
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.status").value("PAYMENT_APPROVING"));

            releaseToss.countDown();
            assertThat(first.get(30, TimeUnit.SECONDS)).isEqualTo(200);
        } finally {
            releaseToss.countDown();
            pool.shutdownNow();
        }

        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class,
                order.getId())).isEqualTo(1);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);
        assertThat(outboxPayloads("CONFIRM")).hasSize(1);
        assertThat(outboxPayloads("RELEASE")).isEmpty();
    }

    @Test
    void 토스_승인_중에_결제_마감이_지나도_만료되지_않고_승인_결과대로_끝난다() throws Exception {
        // 토스 응답을 기다리는 사이(PAYMENT_APPROVING 커밋 뒤) 마감이 지나고 만료 스케줄러가 돈다
        AtomicReference<OrderStatus> statusDuringToss = new AtomicReference<>();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenAnswer(inv -> {
            jdbc.update("UPDATE orders SET payment_expires_at = ? WHERE id = ?",
                    NOW.minusSeconds(1).toLocalDateTime(), order.getId());
            expiryService.expireDue();
            statusDuringToss.set(statusOf(order));
            return TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT);
        });

        confirm("pk_race").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));

        assertThat(statusDuringToss.get()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);
        assertThat(outboxPayloads("CONFIRM")).hasSize(1);
        assertThat(outboxPayloads("RELEASE")).isEmpty();
    }

    @Test
    void 만료가_주문을_고른_뒤_승인이_먼저_끝나면_만료는_그_주문을_건너뛴다() throws Exception {
        // 만료가 PAYMENT_WAITING 주문 번호를 고른 직후, 잠그기 전에 승인이 끝까지 커밋되는 순서를 만든다.
        // 시계가 고정이라 마감을 지난 값으로 두어 골라지게 한 뒤, 승인 직전에 마감을 되돌렸다가 승인 뒤 다시 지난 값으로 둔다
        jdbc.update("UPDATE orders SET payment_expires_at = ? WHERE id = ?",
                NOW.minusSeconds(1).toLocalDateTime(), order.getId());
        tossReturns(TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT));
        doAnswer(inv -> {
            // 저장소 프록시는 실제 메서드를 대신 부를 수 없어 같은 조건(findDueIds)을 SQL로 고른다
            List<Long> ids = jdbc.queryForList("SELECT id FROM orders WHERE status = 'PAYMENT_WAITING' "
                    + "AND payment_expires_at <= ? ORDER BY payment_expires_at", Long.class, NOW.toLocalDateTime());
            assertThat(ids).contains(order.getId());
            jdbc.update("UPDATE orders SET payment_expires_at = ? WHERE id = ?",
                    NOW.plusMinutes(10).toLocalDateTime(), order.getId());
            confirm("pk_after_pick").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAID"));
            // 승인이 끝나면 다시 마감 지남으로 둔다. 만료가 상태(PAYMENT_WAITING) 확인만으로 건너뛰는지 본다
            jdbc.update("UPDATE orders SET payment_expires_at = ? WHERE id = ?",
                    NOW.minusSeconds(1).toLocalDateTime(), order.getId());
            return ids;
        }).when(orderRepository).findDueIds(any(), any(), any());

        expiryService.expireDue();

        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAID);
        assertThat(outboxPayloads("CONFIRM")).hasSize(1);
        assertThat(outboxPayloads("RELEASE")).isEmpty();
    }

    @Test
    void 다른_paymentKey로_오면_409_PAYMENT_INFO_MISMATCH() throws Exception {
        tossReturns(TossConfirmResult.error(TossOutcome.UNKNOWN, null, null));
        confirm("pk_first").andExpect(status().isOk());

        confirm("pk_other")
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INFO_MISMATCH"));
    }

    @Test
    void 다른_주문이_쓴_paymentKey면_409_PAYMENT_INFO_MISMATCH이고_토스를_부르지_않는다() throws Exception {
        tossReturns(TossConfirmResult.approved("DONE", "카드", APPROVED_AT, RECEIPT));
        confirm("pk_shared").andExpect(status().isOk());
        Order other = waitingOrder(customer);

        confirm(customer, other.getId(), body("pk_shared", other.getOrderNo(), 3400))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PAYMENT_INFO_MISMATCH"));

        assertThat(statusOf(other)).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class,
                other.getId())).isZero();
        verify(toss, times(1)).confirm(anyString(), anyString(), anyLong(), anyString());
    }

    @Test
    void 승인_전_검사에_걸리면_토스를_부르지_않고_상태도_그대로() throws Exception {
        confirm(UUID.randomUUID().toString(), order.getId(), body("pk", order.getOrderNo(), 3400))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"));
        confirm(customer, Long.MAX_VALUE, body("pk", order.getOrderNo(), 3400))
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
        confirm(customer, order.getId(), body("pk", "FL-20261020-999999", 3400))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("ORDER_NO_MISMATCH"));
        confirm(customer, order.getId(), body("pk", order.getOrderNo(), 3401))
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("PAYMENT_AMOUNT_MISMATCH"));
        confirm(customer, order.getId(), "{\"orderNo\":\"" + order.getOrderNo() + "\",\"amount\":3400}")
                .andExpect(status().isBadRequest()).andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));

        verify(toss, never()).confirm(any(), any(), anyLong(), any());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, order.getId())).isZero();
    }

    @Test
    void 결제할_수_없는_상태면_409_ORDER_NOT_PAYABLE() throws Exception {
        Order reserving = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(),
                1L, "점포", 15L, 2));
        jdbc.update("UPDATE orders SET order_no = ? WHERE id = ?", "FL-20261020-" + reserving.getId(), reserving.getId());

        confirm(customer, reserving.getId(), body("pk", "FL-20261020-" + reserving.getId(), 3400))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PAYABLE"));
        verify(toss, never()).confirm(any(), any(), anyLong(), any());
    }

    @Test
    void 결제_마감이_지났으면_409_ORDER_EXPIRED() throws Exception {
        jdbc.update("UPDATE orders SET payment_expires_at = ? WHERE id = ?", "2026-10-20 15:31:55", order.getId());

        confirm("pk").andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));

        verify(toss, never()).confirm(any(), any(), anyLong(), any());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYMENT_WAITING);
    }

    @Test
    void 토스_시크릿_키가_없으면_500이고_주문은_바꾸지_않는다() throws Exception {
        when(toss.isConfigured()).thenReturn(false);

        confirm("pk").andExpect(status().isInternalServerError());

        verify(toss, never()).confirm(any(), any(), anyLong(), any());
        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, order.getId())).isZero();
    }

    @Test
    void 토스_호출이_예외로_끝나도_승인_중으로_남는다() throws Exception {
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString())).thenThrow(new IllegalStateException("boom"));

        confirm("pk_boom").andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PAYMENT_APPROVING"));

        assertThat(statusOf(order)).isEqualTo(OrderStatus.PAYMENT_APPROVING);
    }
}
