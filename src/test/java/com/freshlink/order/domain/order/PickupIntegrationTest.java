package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.notification.NotificationKind;
import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.common.error.OrderErrorCode;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.ArgumentMatchers.argThat;
import static org.mockito.Mockito.doThrow;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A4 픽업 기한·A5 픽업 완료를 실제 MySQL에서 확인한다. 스케줄러는 끄고 서비스를 바로 부른다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class PickupIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    /** 10/25 15:00 KST. 기한 10/25 23:59:59는 아직 안 지났고 10/24 23:59:59는 지났다 */
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 15, 0, 0, 0, KST);
    private static final OffsetDateTime TODAY_END = OffsetDateTime.of(2026, 10, 25, 23, 59, 59, 0, KST);
    private static final OffsetDateTime YESTERDAY_END = TODAY_END.minusDays(1);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderService orderService;
    @Autowired
    PickupDeadlineService pickupDeadlineService;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @MockitoSpyBean
    OutboxWriter outboxWriter;
    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.from(NOW), ZoneId.of("Asia/Seoul"));
    }

    /** 테스트마다 새 점포 번호를 써서 다른 테스트 데이터와 섞이지 않게 한다 */
    private long storeId() {
        return 2_000_000L + (System.nanoTime() % 1_000_000L);
    }

    /** 상태·기한을 SQL로 맞춘 주문 */
    private long order(long storeId, String status, OffsetDateTime deadline) {
        Order o = orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), storeId, "점포", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, pickup_deadline = ?, product_name = '매운 컵라면 115g', "
                        + "unit_price = 1700, amount = 3400, order_no = ?, paid_at = ?, payment_expires_at = ? WHERE id = ?",
                status, deadline == null ? null : deadline.toLocalDateTime(), "FL-20261025-" + o.getId(),
                "PAID".equals(status) || "READY_FOR_PICKUP".equals(status) ? NOW.minusHours(1).toLocalDateTime() : null,
                "PAYMENT_WAITING".equals(status) ? NOW.plusMinutes(5).toLocalDateTime() : null, o.getId());
        return o.getId();
    }

    private Order reload(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    private ResultActions pickup(long storeId, long orderId) throws Exception {
        return mockMvc.perform(post("/owner/orders/" + orderId + "/pickup")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(storeId)));
    }

    private List<ReleaseMessage> releasesOf(long orderId) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = 'RELEASE'", String.class).stream()
                .map(p -> MessageJson.fromJson(p, ReleaseMessage.class))
                .filter(m -> m.orderId().equals(orderId))
                .toList();
    }

    // ---------------------------------------------------------------- A5 픽업 확인

    @Test
    void 기한_이내_픽업_준비_완료_주문을_픽업하면_PICKED_UP이고_신규_주문_목록에서_빠진다() throws Exception {
        long store = storeId();
        long id = order(store, "READY_FOR_PICKUP", TODAY_END);

        pickup(store, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.status").value("PICKED_UP"))
                .andExpect(jsonPath("$.pickedUpAt").value("2026-10-25T15:00:00+09:00"));

        Order saved = reload(id);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(saved.getPickedUpAt()).isEqualTo(NOW.toInstant().atOffset(saved.getPickedUpAt().getOffset()));
        mockMvc.perform(get("/owner/orders").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }

    @Test
    void 다른_점포_주문은_403이고_그대로() throws Exception {
        long store = storeId();
        long id = order(store, "READY_FOR_PICKUP", TODAY_END);

        pickup(store + 1, id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ORDER_ACCESS_DENIED"));

        assertThat(reload(id).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void 이미_픽업한_주문이나_결제_전_주문은_409_ORDER_NOT_PICKABLE() throws Exception {
        long store = storeId();
        long picked = order(store, "READY_FOR_PICKUP", TODAY_END);
        pickup(store, picked).andExpect(status().isOk());
        long waiting = order(store, "PAYMENT_WAITING", TODAY_END);

        pickup(store, picked).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKABLE"));
        pickup(store, waiting).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKABLE"));
        assertThat(reload(waiting).getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
    }

    @Test
    void 기한이_지났으면_스케줄러가_안_돌았어도_409_PICKUP_DEADLINE_PASSED() throws Exception {
        long store = storeId();
        long id = order(store, "READY_FOR_PICKUP", YESTERDAY_END);

        pickup(store, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PICKUP_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.message").value("픽업 기한이 지난 주문입니다"));

        assertThat(reload(id).getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void 기한_시각_그대로면_픽업할_수_있다() throws Exception {
        long store = storeId();
        long id = order(store, "READY_FOR_PICKUP", NOW);

        pickup(store, id).andExpect(status().isOk());
    }

    @Test
    void 없는_주문은_404_점주가_아니면_403() throws Exception {
        pickup(storeId(), 99_999_999L).andExpect(status().isNotFound());
        mockMvc.perform(post("/owner/orders/1/pickup").header("X-User-Role", "CUSTOMER").header("X-Store-Id", "1"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    @Test
    void 같은_주문을_동시에_두_번_픽업해도_한_번만_바뀐다() throws Exception {
        long store = storeId();
        long id = order(store, "READY_FOR_PICKUP", TODAY_END);
        ExecutorService pool = Executors.newFixedThreadPool(2);
        CountDownLatch start = new CountDownLatch(1);
        List<Future<String>> results = new ArrayList<>();
        for (int i = 0; i < 2; i++) {
            results.add(pool.submit(() -> {
                start.await();
                try {
                    orderService.pickUp(store, id);
                    return "OK";
                } catch (BusinessException e) {
                    return e.code();
                }
            }));
        }
        start.countDown();
        List<String> outcomes = new ArrayList<>();
        for (Future<String> f : results) {
            outcomes.add(f.get());
        }
        pool.shutdown();

        assertThat(outcomes).containsExactlyInAnyOrder("OK", OrderErrorCode.ORDER_NOT_PICKABLE.name());
        assertThat(reload(id).getStatus()).isEqualTo(OrderStatus.PICKED_UP);
    }

    // ---------------------------------------------------------------- A4 기한 스케줄러

    @Test
    void 기한이_지난_결제_완료_주문은_PICKUP_EXPIRED이고_쪽지는_없다() {
        long store = storeId();
        long due = order(store, "PAID", YESTERDAY_END);

        pickupDeadlineService.expireDue();

        Order saved = reload(due);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.PICKUP_EXPIRED);
        assertThat(saved.getClosedAt()).isNotNull();
        assertThat(releasesOf(due)).isEmpty();
    }

    @Test
    void 기한이_지난_결제_대기_주문은_EXPIRED_사유_PICKUP_DEADLINE과_RELEASE_한_번() {
        long store = storeId();
        long due = order(store, "PAYMENT_WAITING", YESTERDAY_END);

        pickupDeadlineService.expireDue();
        pickupDeadlineService.expireDue();

        Order saved = reload(due);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(saved.getFailureReason()).isEqualTo(OrderFailureReason.PICKUP_DEADLINE);
        assertThat(releasesOf(due)).extracting(ReleaseMessage::reason).containsExactly(ReleaseReason.EXPIRED);
    }

    @Test
    void 맨_앞_묶음이_계속_실패해도_다음_묶음을_이어서_보고_뒤_주문을_닫는다() {
        // 다른 테스트가 남긴 주문보다 앞에 오게 아주 이른 기한을 쓴다. 앞 2건(결제 대기)은 RELEASE 저장이 계속 실패한다
        long store = storeId();
        OffsetDateTime early = OffsetDateTime.of(2020, 1, 1, 23, 59, 59, 0, KST);
        long failing1 = order(store, "PAYMENT_WAITING", early);
        long failing2 = order(store, "PAYMENT_WAITING", early.plusSeconds(1));
        long next = order(store, "PAID", early.plusSeconds(2));
        doThrow(new IllegalStateException("outbox 저장 실패 흉내")).when(outboxWriter).save(anyString(),
                argThat(m -> m instanceof ReleaseMessage r && List.of(failing1, failing2).contains(r.orderId())));

        int closed = pickupDeadlineService.expireDue(2);

        assertThat(closed).isGreaterThanOrEqualTo(1);
        assertThat(reload(next).getStatus()).isEqualTo(OrderStatus.PICKUP_EXPIRED);
        assertThat(reload(failing1).getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(reload(failing2).getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(releasesOf(failing1)).isEmpty();
    }

    @Test
    void 기한이_안_지났거나_다른_상태면_건드리지_않는다() {
        long store = storeId();
        long notYet = order(store, "PAID", TODAY_END);
        long exactlyNow = order(store, "PAID", NOW);
        long picked = order(store, "PICKED_UP", YESTERDAY_END);
        long approving = order(store, "PAYMENT_APPROVING", YESTERDAY_END);

        pickupDeadlineService.expireDue();

        assertThat(reload(notYet).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reload(exactlyNow).getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(reload(picked).getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(reload(approving).getStatus()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
    }

    @Test
    void 점주_목록과_주문_조회에_픽업_기한이_KST로_나온다() throws Exception {
        long store = storeId();
        long id = order(store, "PAID", TODAY_END);
        String customer = reload(id).getCustomerId();

        mockMvc.perform(get("/owner/orders").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$[0].pickupDeadline").value("2026-10-25T23:59:59+09:00"));
        mockMvc.perform(get("/orders/" + id).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.pickupDeadline").value("2026-10-25T23:59:59+09:00"))
                .andExpect(jsonPath("$.pickedUpAt").isEmpty());
    }

    // ---------------------------------------------------------------- N-02 픽업 준비 완료

    private ResultActions ready(long storeId, long orderId) throws Exception {
        return mockMvc.perform(post("/owner/orders/" + orderId + "/ready")
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(storeId)));
    }

    private List<NotificationMessage> notificationsOf(long orderId) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = 'NOTIFICATION'", String.class).stream()
                .map(p -> MessageJson.fromJson(p, NotificationMessage.class))
                .filter(m -> orderId == m.orderId())
                .toList();
    }

    @Test
    void 결제_완료_주문을_픽업_준비_완료로_바꾸면_고객에게_알림_쪽지가_한_번_남는다() throws Exception {
        long store = storeId();
        long id = order(store, "PAID", TODAY_END);

        ready(store, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));
        // 두 번 눌러도 상태·알림은 한 번
        ready(store, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("READY_FOR_PICKUP"));

        Order saved = reload(id);
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(notificationsOf(id)).singleElement().satisfies(m -> {
            assertThat(m.kind()).isEqualTo(NotificationKind.PICKUP_READY);
            assertThat(m.userId()).isEqualTo(saved.getCustomerId());
            assertThat(m.storeId()).isEqualTo(store);
            assertThat(m.storeName()).isEqualTo("점포");
            assertThat(m.orderNo()).isEqualTo(saved.getOrderNo());
            assertThat(m.productName()).isEqualTo("매운 컵라면 115g");
            assertThat(m.quantity()).isEqualTo(2);
        });
    }

    @Test
    void 준비_완료를_거치지_않은_결제_완료_주문은_픽업할_수_없다() throws Exception {
        long store = storeId();
        long id = order(store, "PAID", TODAY_END);

        pickup(store, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_PICKABLE"));
        ready(store, id).andExpect(status().isOk());
        pickup(store, id).andExpect(status().isOk()).andExpect(jsonPath("$.status").value("PICKED_UP"));
    }

    @Test
    void 준비_완료는_다른_점포_403_결제_전_409_기한_지남_409_점주가_아니면_403() throws Exception {
        long store = storeId();
        long paid = order(store, "PAID", TODAY_END);
        long waiting = order(store, "PAYMENT_WAITING", TODAY_END);
        long past = order(store, "PAID", YESTERDAY_END);

        ready(store + 1, paid).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ORDER_ACCESS_DENIED"));
        ready(store, waiting).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_READYABLE"));
        ready(store, past).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PICKUP_DEADLINE_PASSED"));
        ready(store, 99_999_999L).andExpect(status().isNotFound());
        mockMvc.perform(post("/owner/orders/" + paid + "/ready").header("X-User-Role", "CUSTOMER")
                        .header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isForbidden());
        assertThat(notificationsOf(paid)).isEmpty();
        assertThat(notificationsOf(past)).isEmpty();
    }

    @Test
    void 기한이_지난_픽업_준비_완료_주문도_PICKUP_EXPIRED() {
        long store = storeId();
        long due = order(store, "READY_FOR_PICKUP", YESTERDAY_END);

        pickupDeadlineService.expireDue();

        assertThat(reload(due).getStatus()).isEqualTo(OrderStatus.PICKUP_EXPIRED);
        assertThat(releasesOf(due)).isEmpty();
    }

    @Test
    void 점주_신규_주문_목록과_픽업_대기_구분에_준비_완료_주문도_나온다() throws Exception {
        long store = storeId();
        order(store, "PAID", TODAY_END);
        order(store, "READY_FOR_PICKUP", TODAY_END);

        mockMvc.perform(get("/owner/orders").header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.length()").value(2));
        mockMvc.perform(get("/owner/orders").param("tab", "PICKUP_WAITING")
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.content.length()").value(2));
    }
}
