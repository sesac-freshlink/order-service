package com.freshlink.order.domain.order;

import com.freshlink.common.message.BaseMessage;
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
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** 결제 대기 10분 만료를 실제 MySQL에서 확인한다. 스케줄러는 끄고 서비스를 바로 부른다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@Import(TestcontainersConfiguration.class)
class OrderExpiryIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 20, 15, 50, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    OrderExpiryService expiryService;
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

    /** 상태와 결제 마감을 SQL로 맞춘 주문 */
    private long order(String status, OffsetDateTime expiresAt) {
        Order o = orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 1L, "점포", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, payment_expires_at = ?, amount = 3400 WHERE id = ?",
                status, expiresAt == null ? null : expiresAt.toLocalDateTime(), o.getId());
        return o.getId();
    }

    private Order reload(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    private List<ReleaseMessage> releasesOf(long orderId) {
        return jdbc.queryForList("SELECT payload FROM outbox WHERE message_type = 'RELEASE'", String.class).stream()
                .map(p -> (BaseMessage) MessageJson.fromJson(p))
                .filter(m -> m instanceof ReleaseMessage r && r.orderId().equals(orderId))
                .map(m -> (ReleaseMessage) m)
                .toList();
    }

    @Test
    void 마감이_지난_결제_대기_주문은_EXPIRED와_RELEASE_EXPIRED() {
        long due = order("PAYMENT_WAITING", NOW.minusSeconds(1));
        long exactlyNow = order("PAYMENT_WAITING", NOW);

        expiryService.expireDue();

        for (long id : List.of(due, exactlyNow)) {
            Order o = reload(id);
            assertThat(o.getStatus()).isEqualTo(OrderStatus.EXPIRED);
            assertThat(o.getFailureReason()).isEqualTo(OrderFailureReason.EXPIRED);
            assertThat(o.getClosedAt()).isNotNull();
            List<ReleaseMessage> releases = releasesOf(id);
            assertThat(releases).hasSize(1);
            assertThat(releases.get(0).reason()).isEqualTo(ReleaseReason.EXPIRED);
            assertThat(releases.get(0).storeId()).isEqualTo(1L);
        }
    }

    @Test
    void 마감_전이거나_결제_대기가_아니면_건드리지_않는다() {
        long notYet = order("PAYMENT_WAITING", NOW.plusSeconds(1));
        long approving = order("PAYMENT_APPROVING", NOW.minusMinutes(5));
        long reserving = order("RESERVING", null);
        long paid = order("PAID", NOW.minusMinutes(5));

        expiryService.expireDue();

        assertThat(reload(notYet).getStatus()).isEqualTo(OrderStatus.PAYMENT_WAITING);
        assertThat(reload(approving).getStatus()).isEqualTo(OrderStatus.PAYMENT_APPROVING);
        assertThat(reload(reserving).getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(reload(paid).getStatus()).isEqualTo(OrderStatus.PAID);
        for (long id : List.of(notYet, approving, reserving, paid)) {
            assertThat(releasesOf(id)).isEmpty();
        }
    }

    @Test
    void 두_번_돌려도_한_번만_만료하고_RELEASE도_한_건() {
        long due = order("PAYMENT_WAITING", NOW.minusMinutes(1));

        expiryService.expireDue();
        expiryService.expireDue();

        assertThat(reload(due).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        assertThat(releasesOf(due)).hasSize(1);
    }

    @Test
    void 한_번에_정해진_수만_처리하고_나머지는_다음_차례에() {
        List<Long> ids = new java.util.ArrayList<>();
        for (int i = 0; i < 3; i++) {
            ids.add(order("PAYMENT_WAITING", NOW.minusMinutes(1)));
        }

        int expired = expiryService.expireDue(2);

        assertThat(expired).isEqualTo(2);
        expiryService.expireDue(2);
        for (long id : ids) {
            assertThat(reload(id).getStatus()).isEqualTo(OrderStatus.EXPIRED);
        }
    }
}
