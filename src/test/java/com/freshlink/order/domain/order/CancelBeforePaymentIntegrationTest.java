package com.freshlink.order.domain.order;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.List;
import java.util.UUID;
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

/** A7 결제 전 고객 취소를 실제 MySQL에서 확인한다. 스케줄러는 끄고 시계는 고정한다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CancelBeforePaymentIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    /** 10/25 15:00 KST */
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 25, 15, 0, 0, 0, KST);
    private static final OffsetDateTime TODAY_END = OffsetDateTime.of(2026, 10, 25, 23, 59, 59, 0, KST);

    @Autowired
    MockMvc mockMvc;
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

    private final String customer = UUID.randomUUID().toString();
    private final long store = 4_000_000L + (System.nanoTime() % 1_000_000L);

    /** 상태·결제 마감·픽업 기한을 SQL로 맞춘 주문 (3400원) */
    private long order(String status, OffsetDateTime paymentExpiresAt, OffsetDateTime deadline) {
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(),
                store, "프레시 강남점", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, payment_expires_at = ?, pickup_deadline = ?, "
                        + "product_name = '매운 컵라면 115g', unit_price = 1700, amount = 3400, order_no = ? WHERE id = ?",
                status, paymentExpiresAt.toLocalDateTime(), deadline.toLocalDateTime(), "FL-20261025-" + o.getId(),
                o.getId());
        return o.getId();
    }

    private long waiting() {
        return order("PAYMENT_WAITING", NOW.plusMinutes(5), TODAY_END);
    }

    private ResultActions cancel(String customerId, long orderId) throws Exception {
        return mockMvc.perform(post("/orders/" + orderId + "/payment/cancel")
                .header("X-User-Id", customerId).header("X-User-Role", "CUSTOMER"));
    }

    private List<ReleaseMessage> releasesOf(long orderId) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = 'RELEASE'", String.class).stream()
                .map(p -> MessageJson.fromJson(p, ReleaseMessage.class))
                .filter(m -> m.orderId().equals(orderId))
                .toList();
    }

    @Test
    void 결제_대기_주문을_취소하면_CANCELED와_RELEASE_CANCELED가_한_번에_저장된다() throws Exception {
        long id = waiting();

        cancel(customer, id).andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.status").value("CANCELED"))
                .andExpect(jsonPath("$.amount").value(3400));

        Order saved = orderRepository.findById(id).orElseThrow();
        assertThat(saved.getStatus()).isEqualTo(OrderStatus.CANCELED);
        assertThat(saved.getClosedAt()).isNotNull();
        assertThat(releasesOf(id)).singleElement().satisfies(m -> {
            assertThat(m.reason()).isEqualTo(ReleaseReason.CANCELED);
            assertThat(m.storeId()).isEqualTo(store);
        });
    }

    @Test
    void 남의_주문은_403_없는_주문은_404() throws Exception {
        long id = waiting();

        cancel(UUID.randomUUID().toString(), id).andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"));
        cancel(customer, 99_999_999L).andExpect(status().isNotFound());
        mockMvc.perform(post("/orders/" + id + "/payment/cancel").header("X-User-Role", "OWNER"))
                .andExpect(status().isForbidden());
        assertThat(releasesOf(id)).isEmpty();
    }

    @Test
    void 승인_중이나_결제_완료_재고_확인_중_주문은_409_ORDER_NOT_CANCELABLE() throws Exception {
        for (String s : List.of("PAYMENT_APPROVING", "PAID", "RESERVING", "EXPIRED")) {
            long id = order(s, NOW.plusMinutes(5), TODAY_END);
            cancel(customer, id).andExpect(status().isConflict())
                    .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELABLE"));
            assertThat(orderRepository.findById(id).orElseThrow().getStatus().name()).isEqualTo(s);
            assertThat(releasesOf(id)).isEmpty();
        }
    }

    @Test
    void 결제_마감이_없는_결제_대기_주문은_500이_아니라_409_ORDER_NOT_CANCELABLE() throws Exception {
        long id = waiting();
        jdbc.update("UPDATE orders SET payment_expires_at = NULL WHERE id = ?", id);

        cancel(customer, id).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_CANCELABLE"));
        assertThat(releasesOf(id)).isEmpty();
    }

    @Test
    void 결제_시간이나_픽업_기한이_지났으면_스케줄러가_안_돌았어도_409() throws Exception {
        long expired = order("PAYMENT_WAITING", NOW, TODAY_END);
        long pastDeadline = order("PAYMENT_WAITING", NOW.plusMinutes(5), TODAY_END.minusDays(1));

        cancel(customer, expired).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_EXPIRED"));
        cancel(customer, pastDeadline).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("PICKUP_DEADLINE_PASSED"));
    }

    @Test
    void 취소한_주문은_취소_환불_구분에_나오고_점주_매출의_취소_건수에_들어간다() throws Exception {
        long id = waiting();
        cancel(customer, id).andExpect(status().isOk());

        mockMvc.perform(get("/orders").param("tab", "CANCELED")
                        .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(jsonPath("$.content.length()").value(1))
                .andExpect(jsonPath("$.content[0].status").value("CANCELED"));
        mockMvc.perform(get("/owner/orders/sales").param("from", "2026-10-25").param("to", "2026-10-25")
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(jsonPath("$.periods[0].canceledCount").value(1))
                .andExpect(jsonPath("$.periods[0].paidAmount").value(0));
    }
}
