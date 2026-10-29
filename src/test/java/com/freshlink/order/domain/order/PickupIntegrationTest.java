package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
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
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A4 픽업 기한·A5 픽업 완료를 실제 MySQL에서 확인한다. 스케줄러는 끄고 서비스를 바로 부른다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
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
                "PAID".equals(status) ? NOW.minusHours(1).toLocalDateTime() : null,
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
    void 기한_이내_결제_완료_주문을_픽업하면_PICKED_UP이고_신규_주문_목록에서_빠진다() throws Exception {
        long store = storeId();
        long id = order(store, "PAID", TODAY_END);

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
        long id = order(store, "PAID", TODAY_END);

        pickup(store + 1, id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_ORDER_ACCESS_DENIED"));

        assertThat(reload(id).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 이미_픽업한_주문이나_결제_전_주문은_409_ORDER_NOT_PICKABLE() throws Exception {
        long store = storeId();
        long picked = order(store, "PAID", TODAY_END);
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
        long id = order(store, "PAID", YESTERDAY_END);

        pickup(store, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PICKUP_DEADLINE_PASSED"))
                .andExpect(jsonPath("$.message").value("픽업 기한이 지난 주문입니다"));

        assertThat(reload(id).getStatus()).isEqualTo(OrderStatus.PAID);
    }

    @Test
    void 기한_시각_그대로면_픽업할_수_있다() throws Exception {
        long store = storeId();
        long id = order(store, "PAID", NOW);

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
        long id = order(store, "PAID", TODAY_END);
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
}
