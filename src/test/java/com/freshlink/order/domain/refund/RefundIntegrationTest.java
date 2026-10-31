package com.freshlink.order.domain.refund;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.order.PickupDeadlineService;
import com.freshlink.order.domain.payment.toss.TossCancelOutcome;
import com.freshlink.order.domain.payment.toss.TossCancelResult;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A8 결제 후 취소·A9 환불·A10 재고 복원 쪽지를 실제 MySQL에서 확인한다.
 * 토스 클라이언트는 목이라 실제 토스에 접속하지 않는다. 스케줄러는 끄고 서비스를 바로 부른다
 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class RefundIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    /** 10/25 15:00 KST. 기한 10/25 23:59:59는 아직 안 지났고 10/24 23:59:59는 지났다 */
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 15, 0, 0, 0, KST);
    private static final OffsetDateTime TODAY_END = OffsetDateTime.of(2026, 10, 25, 23, 59, 59, 0, KST);
    private static final OffsetDateTime YESTERDAY_END = TODAY_END.minusDays(1);
    private static final OffsetDateTime CANCELED_AT = OffsetDateTime.of(2026, 10, 25, 15, 0, 3, 0, KST);
    private static final String CUSTOMER_BODY = "{\"reason\":\"CHANGE_OF_MIND\"}";
    private static final String OWNER_BODY = "{\"reason\":\"OUT_OF_STOCK\"}";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    RefundRepository refundRepository;
    @Autowired
    RefundAutoSyncService refundAutoSyncService;
    @Autowired
    PickupDeadlineService pickupDeadlineService;
    @Autowired
    JdbcTemplate jdbc;

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
    long store;

    @BeforeEach
    void setUp() {
        when(toss.isConfigured()).thenReturn(true);
        // 다른 테스트가 남긴 확인 중 환불도 조회되므로 기본은 결과 불명이다
        when(toss.lookupCancel(anyString())).thenReturn(TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, "기본"));
        customer = UUID.randomUUID().toString();
        store = 3_000_000L + (System.nanoTime() % 1_000_000L);
    }

    /** 결제 완료 주문 (3400원)과 승인 완료 결제 기록. 상태·기한·환불 상태는 SQL로 맞춘다 */
    private long paidOrder(OffsetDateTime deadline) {
        return order("PAID", deadline, "NONE");
    }

    private long order(String status, OffsetDateTime deadline, String refundStatus) {
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(),
                store, "프레시 강남점", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, refund_status = ?, pickup_deadline = ?, "
                        + "product_name = '매운 컵라면 115g', unit_price = 1700, amount = 3400, order_no = ?, paid_at = ? "
                        + "WHERE id = ?",
                status, refundStatus, deadline.toLocalDateTime(), "FL-20261025-" + o.getId(),
                NOW.minusHours(1).toLocalDateTime(), o.getId());
        jdbc.update("INSERT INTO payment (order_id, payment_key, toss_idempotency_key, amount, status, toss_status, "
                        + "approved_at, created_at, updated_at) VALUES (?, ?, ?, 3400, 'DONE', 'DONE', ?, ?, ?)",
                o.getId(), "pk_" + o.getId(), UUID.randomUUID().toString(), NOW.minusHours(1).toLocalDateTime(),
                NOW.minusHours(1).toLocalDateTime(), NOW.minusHours(1).toLocalDateTime());
        return o.getId();
    }

    private Order reload(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    private ResultActions customerCancel(String customerId, long orderId, String body) throws Exception {
        return mockMvc.perform(post("/orders/" + orderId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-User-Id", customerId).header("X-User-Role", "CUSTOMER"));
    }

    private ResultActions ownerCancel(long storeId, long orderId, String body) throws Exception {
        return mockMvc.perform(post("/owner/orders/" + orderId + "/cancel")
                .contentType(MediaType.APPLICATION_JSON).content(body)
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(storeId)));
    }

    private List<ReturnMessage> returnsOf(long orderId) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = 'RETURN'", String.class).stream()
                .map(p -> MessageJson.fromJson(p, ReturnMessage.class))
                .filter(m -> m.orderId().equals(orderId))
                .toList();
    }

    private void tossCancels(TossCancelResult result) {
        when(toss.cancel(anyString(), anyString(), anyString())).thenReturn(result);
    }

    private List<Refund> attempts(long orderId) {
        return refundRepository.findAll().stream().filter(r -> r.getOrderId().equals(orderId)).toList();
    }

    // ---------------------------------------------------------------- A8 고객 취소

    @Test
    void 고객이_취소하면_전액_환불되고_REFUNDED와_RETURN이_한_번에_저장된다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));

        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.refund.requestedBy").value("CUSTOMER"))
                .andExpect(jsonPath("$.refund.reason").value("CHANGE_OF_MIND"))
                .andExpect(jsonPath("$.refund.reasonLabel").value("단순 변심"))
                .andExpect(jsonPath("$.refund.refundAmount").value(3400))
                .andExpect(jsonPath("$.refund.canceledAt").value("2026-10-25T15:00:03+09:00"));

        Order saved = reload(id);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(saved.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(returnsOf(id)).singleElement().satisfies(m -> assertThat(m.storeId()).isEqualTo(store));
        ArgumentCaptor<String> key = ArgumentCaptor.forClass(String.class);
        verify(toss).cancel(eq("pk_" + id), eq("단순 변심"), key.capture());
        assertThat(attempts(id)).singleElement().satisfies(r -> {
            assertThat(r.getTossIdempotencyKey()).isEqualTo(key.getValue());
            assertThat(r.getAttemptNo()).isEqualTo(1);
            assertThat(r.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        });
    }

    @Test
    void 주문_상세에_요청자_사유_환불금액_처리_상태가_나온다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));
        customerCancel(customer, id, "{\"reason\":\"ETC\",\"reasonDetail\":\"  일정이 바뀌었어요 \"}")
                .andExpect(status().isOk());

        mockMvc.perform(get("/orders/" + id).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.refund.reason").value("ETC"))
                .andExpect(jsonPath("$.refund.reasonDetail").value("일정이 바뀌었어요"))
                .andExpect(jsonPath("$.refund.refundAmount").value(3400))
                .andExpect(jsonPath("$.refund.requestedAt").value("2026-10-25T15:00:00+09:00"));
        verify(toss).cancel(eq("pk_" + id), eq("기타: 일정이 바뀌었어요"), anyString());
    }

    @Test
    void 남의_주문은_403_없는_주문은_404이고_토스를_부르지_않는다() throws Exception {
        long id = paidOrder(TODAY_END);

        customerCancel(UUID.randomUUID().toString(), id, CUSTOMER_BODY).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"));
        customerCancel(customer, 99_999_999L, CUSTOMER_BODY).andExpect(status().isNotFound());

        verify(toss, never()).cancel(anyString(), anyString(), anyString());
        assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    void 사유가_없거나_점주_사유이거나_기타에_상세가_없으면_400() throws Exception {
        long id = paidOrder(TODAY_END);

        customerCancel(customer, id, "{}").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("reason: 고를 수 있는 취소 사유가 아닙니다"));
        customerCancel(customer, id, OWNER_BODY).andExpect(status().isBadRequest());
        customerCancel(customer, id, "{\"reason\":\"ETC\",\"reasonDetail\":\"   \"}")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("reasonDetail: 기타 사유를 입력해 주세요"));
        customerCancel(customer, id, "{\"reason\":\"ETC\",\"reasonDetail\":\"" + "가".repeat(201) + "\"}")
                .andExpect(status().isBadRequest());

        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void 기한이_지났으면_스케줄러가_안_돌았어도_409_PICKUP_DEADLINE_PASSED() throws Exception {
        long id = paidOrder(YESTERDAY_END);

        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PICKUP_DEADLINE_PASSED"));

        assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.NONE);
    }

    @Test
    void 픽업_준비_완료_주문도_고객_점주_모두_취소해_환불한다() throws Exception {
        long byCustomer = order("READY_FOR_PICKUP", TODAY_END, "NONE");
        long byOwner = order("READY_FOR_PICKUP", TODAY_END, "NONE");
        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));

        customerCancel(customer, byCustomer, CUSTOMER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
        ownerCancel(store, byOwner, OWNER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));

        assertThat(returnsOf(byCustomer)).hasSize(1);
        assertThat(returnsOf(byOwner)).hasSize(1);
    }

    @Test
    void 환불이_시작된_픽업_준비_완료_주문은_픽업할_수_없고_취소_환불_구분에_나온다() throws Exception {
        long ready = order("READY_FOR_PICKUP", TODAY_END, "NONE");
        tossCancels(TossCancelResult.error(TossCancelOutcome.DEFINITE_FAILURE, "NOT_CANCELABLE_PAYMENT", "취소 불가"));
        ownerCancel(store, ready, OWNER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"))
                .andExpect(jsonPath("$.refundStatus").value("FAILED"));

        mockMvc.perform(post("/owner/orders/" + ready + "/pickup").header("X-User-Role", "OWNER")
                        .header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKABLE"));
        mockMvc.perform(get("/owner/orders").param("tab", "CANCELED")
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(ready));
        mockMvc.perform(get("/owner/orders").param("tab", "PICKUP_WAITING")
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.content.length()").value(0));
        mockMvc.perform(get("/orders").param("tab", "CANCELED")
                        .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.content.length()").value(1));
    }

    @Test
    void 픽업_완료_기한_경과_결제_전_주문은_409_ORDER_NOT_CANCELABLE() throws Exception {
        long picked = order("PICKED_UP", TODAY_END, "NONE");
        long expired = order("PICKUP_EXPIRED", YESTERDAY_END, "NONE");
        long waiting = order("PAYMENT_WAITING", TODAY_END, "NONE");

        for (long id : List.of(picked, expired, waiting)) {
            customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELABLE"));
        }
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void 환불을_처리_중이거나_확인_중이면_409_REFUND_IN_PROGRESS() throws Exception {
        long requested = order("PAID", TODAY_END, "REQUESTED");
        long checking = order("PAID", TODAY_END, "CHECKING");

        customerCancel(customer, requested, CUSTOMER_BODY).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_IN_PROGRESS"));
        ownerCancel(store, checking, OWNER_BODY).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("REFUND_IN_PROGRESS"));
    }

    // ---------------------------------------------------------------- A8 점주 취소

    @Test
    void 점주는_자기_점포_픽업_대기_주문을_취소한다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));

        ownerCancel(store, id, OWNER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refund.requestedBy").value("OWNER"))
                .andExpect(jsonPath("$.refund.reasonLabel").value("실제 재고 부족"));

        assertThat(returnsOf(id)).hasSize(1);
    }

    @Test
    void 다른_점포_주문은_403_고객_사유는_400_점주가_아니면_403() throws Exception {
        long id = paidOrder(TODAY_END);

        ownerCancel(store + 1, id, OWNER_BODY).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ORDER_ACCESS_DENIED"));
        ownerCancel(store, id, CUSTOMER_BODY).andExpect(status().isBadRequest());
        mockMvc.perform(post("/owner/orders/" + id + "/cancel").contentType(MediaType.APPLICATION_JSON)
                        .content(OWNER_BODY).header("X-User-Role", "CUSTOMER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));

        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    // ---------------------------------------------------------------- A9 실패·재시도

    @Test
    void 토스가_취소를_거절하면_FAILED이고_재신청은_새_시도와_새_멱등키로_한다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.error(TossCancelOutcome.DEFINITE_FAILURE, "NOT_CANCELABLE_PAYMENT", "취소 불가"));

        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.refundStatus").value("FAILED"));
        assertThat(returnsOf(id)).isEmpty();

        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));
        customerCancel(customer, id, "{\"reason\":\"HARD_TO_VISIT\"}").andExpect(status().isOk())
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.refund.reason").value("HARD_TO_VISIT"));

        List<Refund> tries = attempts(id);
        assertThat(tries).extracting(Refund::getAttemptNo).containsExactlyInAnyOrder(1, 2);
        assertThat(tries).extracting(Refund::getTossIdempotencyKey).doesNotHaveDuplicates();
        assertThat(tries).filteredOn(r -> r.getAttemptNo() == 1).singleElement()
                .satisfies(r -> assertThat(r.getErrorCode()).isEqualTo("NOT_CANCELABLE_PAYMENT"));
        assertThat(returnsOf(id)).hasSize(1);
    }

    @Test
    void 기한_안에_시작해_실패한_환불은_기한이_지나도_다시_시도할_수_있다() throws Exception {
        long id = order("PAID", YESTERDAY_END, "FAILED");
        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));

        ownerCancel(store, id, OWNER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"));
    }

    @Test
    void 환불이_시작된_주문은_픽업할_수_없고_기한_스케줄러도_건드리지_않는다() throws Exception {
        long failed = order("PAID", YESTERDAY_END, "FAILED");
        long requested = order("PAID", TODAY_END, "REQUESTED");

        mockMvc.perform(post("/owner/orders/" + requested + "/pickup").header("X-User-Role", "OWNER")
                        .header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKABLE"));
        pickupDeadlineService.expireDue();

        assertThat(reload(failed).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reload(requested).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    // ---------------------------------------------------------------- A9 결과 불명 → 확인

    @Test
    void 결과_불명이면_CHECKING이고_조회로_취소가_확인되면_완료와_RETURN() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, "ResourceAccessException"));

        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"))
                .andExpect(jsonPath("$.refundStatus").value("CHECKING"));
        assertThat(returnsOf(id)).isEmpty();

        when(toss.lookupCancel("pk_" + id)).thenReturn(TossCancelResult.canceled("CANCELED", CANCELED_AT));
        refundAutoSyncService.syncDue();

        Order saved = reload(id);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(saved.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(returnsOf(id)).hasSize(1);
        // 같은 시도를 새 멱등키로 다시 취소하지 않는다
        verify(toss, times(1)).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void 이미_취소된_결제라는_응답도_실패로_보지_않고_조회로_확인한다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.error(TossCancelOutcome.ALREADY_CANCELED, "ALREADY_CANCELED_PAYMENT", "이미 취소"));

        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk())
                .andExpect(jsonPath("$.refundStatus").value("CHECKING"));
    }

    @Test
    void 조회가_한도까지_계속_취소_안_됨이면_FAILED로_확정한다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.error(TossCancelOutcome.UNKNOWN, "PROVIDER_ERROR", "일시 오류"));
        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk());
        when(toss.lookupCancel("pk_" + id)).thenReturn(TossCancelResult.notCanceled("DONE"));

        for (int i = 0; i < RefundAutoSyncService.MAX_ATTEMPTS; i++) {
            // 시계가 멈춰 있어 다음 차례를 지금으로 당긴다
            jdbc.update("UPDATE refund SET next_sync_at = ? WHERE order_id = ? AND next_sync_at IS NOT NULL",
                    NOW.toLocalDateTime(), id);
            refundAutoSyncService.syncDue();
            if (i < RefundAutoSyncService.MAX_ATTEMPTS - 1) {
                assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.CHECKING);
            }
        }

        assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.FAILED);
        assertThat(attempts(id)).singleElement().satisfies(r -> {
            assertThat(r.getErrorCode()).isEqualTo("NOT_CANCELED");
            assertThat(r.getNextSyncAt()).isNull();
        });
        assertThat(returnsOf(id)).isEmpty();
    }

    @Test
    void 조회_실패만_이어지면_실패로_정하지_않고_확인_중으로_남긴다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, "timeout"));
        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk());
        when(toss.lookupCancel("pk_" + id)).thenReturn(TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, "timeout"));

        for (int i = 0; i < RefundAutoSyncService.MAX_ATTEMPTS; i++) {
            jdbc.update("UPDATE refund SET next_sync_at = ? WHERE order_id = ? AND next_sync_at IS NOT NULL",
                    NOW.toLocalDateTime(), id);
            refundAutoSyncService.syncDue();
        }

        assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.CHECKING);
        assertThat(attempts(id)).singleElement().satisfies(r -> {
            assertThat(r.getSyncAttempts()).isEqualTo(RefundAutoSyncService.MAX_ATTEMPTS);
            assertThat(r.getNextSyncAt()).isNull();
        });
    }

    /** 요청 스레드가 토스 결과를 반영하기 전에 멈춘 시도 (주문 환불 REQUESTED, 시도 REQUESTED, 확인 예약 시각) */
    private long stuckRequest(OffsetDateTime nextSyncAt) {
        long id = order("PAID", TODAY_END, "REQUESTED");
        jdbc.update("INSERT INTO refund (order_id, attempt_no, requested_by, requester_id, reason, amount, "
                        + "toss_idempotency_key, status, requested_at, next_sync_at, created_at, updated_at) "
                        + "VALUES (?, 1, 'CUSTOMER', ?, 'CHANGE_OF_MIND', 3400, ?, 'REQUESTED', ?, ?, ?, ?)",
                id, customer, UUID.randomUUID().toString(), NOW.minusMinutes(5).toLocalDateTime(),
                nextSyncAt.toLocalDateTime(), NOW.minusMinutes(5).toLocalDateTime(), NOW.minusMinutes(5).toLocalDateTime());
        return id;
    }

    @Test
    void 새_시도는_결과를_못_받고_멈출_때를_위해_1분_뒤_확인을_예약해_둔다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.error(TossCancelOutcome.DEFINITE_FAILURE, "NOT_CANCELABLE_PAYMENT", "취소 불가"));
        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk());

        // 실패로 끝난 시도는 예약이 지워진다. 처리 중이던 동안에는 요청 시각 + 1분으로 예약돼 있었다
        assertThat(attempts(id)).singleElement().satisfies(r -> assertThat(r.getNextSyncAt()).isNull());
        Refund fresh = Refund.start(id, 2, CancelRequester.CUSTOMER, customer, CancelReason.CHANGE_OF_MIND, null,
                3400, UUID.randomUUID().toString(), NOW);
        assertThat(fresh.getNextSyncAt()).isEqualTo(NOW.plus(Refund.RECOVERY_DELAY));
    }

    @Test
    void 처리_중에_멈춘_시도는_예약_시각이_지나면_조회로_확인해_완료와_RETURN() throws Exception {
        long id = stuckRequest(NOW.minusSeconds(1));
        when(toss.lookupCancel("pk_" + id)).thenReturn(TossCancelResult.canceled("CANCELED", CANCELED_AT));

        refundAutoSyncService.syncDue();

        Order saved = reload(id);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.REFUNDED);
        assertThat(saved.getRefundStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(returnsOf(id)).hasSize(1);
        // 같은 시도를 다시 취소하지 않는다
        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void 처리_중에_멈춘_시도가_아직_취소_안_됐으면_확인_중으로_넘겨_이어서_조회한다() throws Exception {
        long id = stuckRequest(NOW.minusSeconds(1));
        when(toss.lookupCancel("pk_" + id)).thenReturn(TossCancelResult.notCanceled("DONE"));

        refundAutoSyncService.syncDue();

        assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.CHECKING);
        assertThat(attempts(id)).singleElement().satisfies(r -> {
            assertThat(r.getStatus()).isEqualTo(RefundStatus.CHECKING);
            assertThat(r.getErrorCode()).isEqualTo("STALE_REQUEST");
            assertThat(r.getSyncAttempts()).isEqualTo(1);
            assertThat(r.getNextSyncAt()).isNotNull();
        });
    }

    @Test
    void 예약_시각_전의_처리_중_시도는_요청_스레드에_맡기고_건드리지_않는다() throws Exception {
        long id = stuckRequest(NOW.plusSeconds(30));

        refundAutoSyncService.syncDue();

        assertThat(reload(id).getRefundStatus()).isEqualTo(RefundStatus.REQUESTED);
        verify(toss, never()).lookupCancel("pk_" + id);
    }

    // ---------------------------------------------------------------- A3·A6 목록, 매출

    @Test
    void 환불_진행_주문은_픽업_대기에서_빠지고_취소_환불_구분에_나온다() throws Exception {
        long waiting = paidOrder(TODAY_END);
        long failed = order("PAID", TODAY_END, "FAILED");
        long refunded = order("REFUNDED", TODAY_END, "COMPLETED");
        jdbc.update("UPDATE orders SET cancel_requested_at = ? WHERE id = ?", NOW.minusMinutes(10).toLocalDateTime(), failed);
        jdbc.update("UPDATE orders SET cancel_requested_at = ? WHERE id = ?", NOW.minusMinutes(5).toLocalDateTime(), refunded);

        mockMvc.perform(get("/orders").param("tab", "PICKUP_WAITING")
                        .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].orderId").value(waiting));
        mockMvc.perform(get("/orders").param("tab", "CANCELED")
                        .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.content.length()").value(2));
        mockMvc.perform(get("/owner/orders").param("tab", "CANCELED")
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.content[0].orderId").value(refunded))
                .andExpect(jsonPath("$.content[0].refundStatus").value("COMPLETED"))
                .andExpect(jsonPath("$.content[1].orderId").value(failed))
                .andExpect(jsonPath("$.content[1].refundStatus").value("FAILED"));
        mockMvc.perform(get("/owner/orders").header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value(waiting));
    }

    @Test
    void 매출에_환불_금액과_건수가_환불한_날짜로_들어간다() throws Exception {
        long id = paidOrder(TODAY_END);
        tossCancels(TossCancelResult.canceled("CANCELED", CANCELED_AT));
        customerCancel(customer, id, CUSTOMER_BODY).andExpect(status().isOk());

        mockMvc.perform(get("/owner/orders/sales").param("from", "2026-10-25").param("to", "2026-10-25")
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods[0].paidAmount").value(3400))
                .andExpect(jsonPath("$.periods[0].refundAmount").value(3400))
                .andExpect(jsonPath("$.periods[0].netAmount").value(0))
                .andExpect(jsonPath("$.periods[0].paidCount").value(1))
                .andExpect(jsonPath("$.periods[0].refundCount").value(1));
    }
}
