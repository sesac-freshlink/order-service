package com.freshlink.order.domain.payment;

import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.order.ReservationResultHandler;
import com.freshlink.order.domain.payment.toss.TossConfirmResult;
import com.freshlink.order.domain.payment.toss.TossLookupResult;
import com.freshlink.order.domain.payment.toss.TossOutcome;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.eq;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A11 결제 결과 자동 조회를 실제 MySQL에서 확인한다. 토스 클라이언트는 목이고 스케줄러는 끄고 서비스를 바로 부른다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PaymentAutoSyncIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 21, 12, 0, 0, 0, KST);
    private static final OffsetDateTime APPROVED_AT = OffsetDateTime.of(2026, 10, 21, 12, 0, 5, 0, KST);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    ReservationResultHandler reservationResultHandler;
    @Autowired
    PaymentAutoSyncService autoSync;
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
    Order order;
    String paymentKey;

    /** 결제 대기 주문을 만들고, 토스 승인이 결과 불명(UNKNOWN)으로 끝나 승인 중에 머물게 한다 */
    @BeforeEach
    void setUp() throws Exception {
        when(toss.isConfigured()).thenReturn(true);
        customer = UUID.randomUUID().toString();
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(),
                1L, "프레시 강남점", 15L, 2));
        jdbc.update("UPDATE orders SET order_no = ? WHERE id = ?", "FL-20261021-" + o.getId(), o.getId());
        reservationResultHandler.handle(ReservedMessage.of(o.getId(), 1L, 15L, "매운 컵라면 115g", 2, 1700L,
                List.of(new ReservedLot(101L, 2, OffsetDateTime.parse("2026-10-25T23:59:59+09:00")))));
        order = orderRepository.findById(o.getId()).orElseThrow();
        paymentKey = "pk_auto_" + order.getId();
        when(toss.confirm(anyString(), anyString(), anyLong(), anyString()))
                .thenReturn(TossConfirmResult.error(TossOutcome.UNKNOWN, null, null));
        mockMvc.perform(post("/orders/" + order.getId() + "/payment/confirm")
                        .contentType(MediaType.APPLICATION_JSON)
                        .content("{\"paymentKey\":\"" + paymentKey + "\",\"orderNo\":\"" + order.getOrderNo()
                                + "\",\"amount\":3400}")
                        .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk());
    }

    private OrderStatus statusOf() {
        return orderRepository.findById(order.getId()).orElseThrow().getStatus();
    }

    private Map<String, Object> paymentRow() {
        return jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", order.getId());
    }

    private long outboxCount(Class<? extends BaseMessage> type) {
        return jdbc.queryForList("SELECT payload FROM outbox", String.class).stream()
                .map(MessageJson::fromJson)
                .filter(type::isInstance)
                .filter(m -> m instanceof ConfirmMessage c ? c.orderId().equals(order.getId())
                        : m instanceof ReleaseMessage r && r.orderId().equals(order.getId()))
                .count();
    }

    private void lookupReturns(TossLookupResult result) {
        when(toss.lookup(paymentKey)).thenReturn(result);
    }

    private TossLookupResult found(String status) {
        return TossLookupResult.found(status, "카드", "DONE".equals(status) ? APPROVED_AT : null,
                null, order.getOrderNo(), 3400L);
    }

    @Test
    void 결과_불명이면_승인_중으로_두고_곧바로_첫_자동_조회를_예약한다() {
        assertThat(statusOf()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        Map<String, Object> row = paymentRow();
        assertThat(row.get("sync_attempts")).isEqualTo(0);
        assertThat(row.get("next_sync_at")).isNotNull();
    }

    @Test
    void 자동_조회에서_DONE이면_PAID와_CONFIRM이고_예약을_지운다() {
        lookupReturns(found("DONE"));

        autoSync.syncDue();

        assertThat(statusOf()).isEqualTo(OrderStatus.PAID);
        assertThat(outboxCount(ConfirmMessage.class)).isEqualTo(1);
        assertThat(paymentRow().get("next_sync_at")).isNull();
    }

    @Test
    void 자동_조회에서_최종_실패면_PAYMENT_FAILED와_RELEASE() {
        lookupReturns(found("ABORTED"));

        autoSync.syncDue();

        assertThat(statusOf()).isEqualTo(OrderStatus.PAYMENT_FAILED);
        assertThat(outboxCount(ReleaseMessage.class)).isEqualTo(1);
        assertThat(paymentRow().get("next_sync_at")).isNull();
    }

    @Test
    void 아직_모르면_30초_뒤로_다시_예약하고_그_전에는_다시_묻지_않는다() {
        lookupReturns(found("IN_PROGRESS"));
        LocalDateTime first = (LocalDateTime) paymentRow().get("next_sync_at");

        autoSync.syncDue();
        autoSync.syncDue();   // 시계가 그대로라 아직 차례가 아니다

        verify(toss, times(1)).lookup(paymentKey);
        assertThat(statusOf()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        Map<String, Object> row = paymentRow();
        assertThat(row.get("sync_attempts")).isEqualTo(1);
        // 첫 예약(결과 불명이 된 시각) 30초 뒤로 다시 예약한다
        assertThat(Duration.between(first, (LocalDateTime) row.get("next_sync_at"))).isEqualTo(Duration.ofSeconds(30));
    }

    @Test
    void 조회에서_못_찾았다는_이유로_실패_처리하지_않는다() {
        lookupReturns(TossLookupResult.notFound("NOT_FOUND_PAYMENT", "없음"));

        autoSync.syncDue();

        assertThat(statusOf()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        assertThat(outboxCount(ReleaseMessage.class)).isZero();
        assertThat(paymentRow().get("sync_attempts")).isEqualTo(1);
    }

    @Test
    void 열한_번째에도_모르면_예약을_지우고_승인_중으로_둔다() {
        // 앞서 10번 물어본 상태로 맞춘다
        jdbc.update("UPDATE payment SET sync_attempts = 10 WHERE order_id = ?", order.getId());
        lookupReturns(found("IN_PROGRESS"));

        autoSync.syncDue();

        assertThat(statusOf()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        Map<String, Object> row = paymentRow();
        assertThat(row.get("sync_attempts")).isEqualTo(11);
        assertThat(row.get("next_sync_at")).isNull();
        assertThat(outboxCount(ReleaseMessage.class)).isZero();

        autoSync.syncDue();
        verify(toss, times(1)).lookup(paymentKey);
    }

    @Test
    void 토스_키가_없으면_묻지_않고_횟수도_쓰지_않는다() {
        when(toss.isConfigured()).thenReturn(false);

        autoSync.syncDue();

        verify(toss, never()).lookup(eq(paymentKey));
        assertThat(paymentRow().get("sync_attempts")).isEqualTo(0);
    }
}
