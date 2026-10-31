package com.freshlink.order.domain.order;

import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import static org.assertj.core.api.Assertions.assertThat;

/** A12 재고 확인 결과 미수신 감지를 실제 MySQL에서 확인한다. 기준 시간은 기본값 5분 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@Import(TestcontainersConfiguration.class)
class ReservingWatchIntegrationTest {

    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 10, 22, 9, 0, 0, 0, ZoneOffset.ofHours(9));

    @Autowired
    ReservingWatchService watch;
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

    /** status와 만든 시각(NOW에서 minutesAgo분 전)을 맞춘 주문 */
    private long order(String status, int minutesAgo) {
        Order o = orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), 1L, "점포", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, created_at = ? WHERE id = ?",
                status, NOW.minusMinutes(minutesAgo).toLocalDateTime(), o.getId());
        return o.getId();
    }

    private Order reload(long id) {
        return orderRepository.findById(id).orElseThrow();
    }

    private long outboxCount() {
        return jdbc.queryForObject("SELECT COUNT(*) FROM outbox", Long.class);
    }

    @Test
    void 기준_시간을_넘긴_RESERVING만_한_번_표시하고_상태는_그대로다() {
        long stuck = order("RESERVING", 6);
        long fresh = order("RESERVING", 4);
        long waiting = order("PAYMENT_WAITING", 30);
        long outboxBefore = outboxCount();

        watch.detect();

        Order s = reload(stuck);
        assertThat(s.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(s.getReservingAlertedAt()).isNotNull();
        assertThat(reload(fresh).getReservingAlertedAt()).isNull();
        assertThat(reload(waiting).getReservingAlertedAt()).isNull();
        // 감지만 한다: 취소·RELEASE 쪽지 없음
        assertThat(outboxCount()).isEqualTo(outboxBefore);
    }

    @Test
    void 이미_경고한_주문은_다시_경고하지_않는다() {
        long stuck = order("RESERVING", 10);

        watch.detect();
        OffsetDateTime first = reload(stuck).getReservingAlertedAt();
        int second = watch.detect();

        assertThat(reload(stuck).getReservingAlertedAt()).isEqualTo(first);
        assertThat(second).isZero();
    }

    @Test
    void 정확히_기준_시간이면_감지한다() {
        long exactly = order("RESERVING", 5);

        watch.detect();

        assertThat(reload(exactly).getReservingAlertedAt()).isNotNull();
    }
}
