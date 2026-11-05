package com.freshlink.order.domain.payment;

import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Instant;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.ZoneOffset;
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
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.never;
import static org.mockito.Mockito.verify;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 3차 1-3 시뮬레이터 주문 결제 완료(/ops/orders/{id}/simulated-payment)와 SIM- 결제 환불을 실제 MySQL에서 확인한다.
 * 토스 클라이언트는 목이고, 한 번도 불리지 않아야 한다
 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false",
        "app.simulated-payment.enabled=true"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class SimulatedPaymentIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 5, 12, 0, 0, 0, KST);

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
    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.from(NOW), ZoneId.of("Asia/Seoul"));
    }

    String customer;

    @BeforeEach
    void setUp() {
        customer = UUID.randomUUID().toString();
    }

    /** 결제 대기 주문 (3400원, 결제 기한 5분 남음, 픽업 기한 오늘 밤) */
    private long waitingOrder() {
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(),
                4L, "프레시링크 문래역점", 15L, 2));
        jdbc.update("UPDATE orders SET status = 'PAYMENT_WAITING', product_name = '매운 컵라면 115g', unit_price = 1700, "
                        + "amount = 3400, order_no = ?, payment_waiting_at = ?, payment_expires_at = ?, pickup_deadline = ? "
                        + "WHERE id = ?",
                "FL-20261105-" + o.getId(), NOW.minusMinutes(5).toLocalDateTime(), NOW.plusMinutes(5).toLocalDateTime(),
                NOW.withHour(23).withMinute(59).withSecond(59).toLocalDateTime(), o.getId());
        return o.getId();
    }

    @Test
    void 결제_대기_주문을_토스_없이_PAID로_바꾸고_CONFIRM과_새주문알림을_남긴다() throws Exception {
        long id = waitingOrder();

        mockMvc.perform(post("/ops/orders/" + id + "/simulated-payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(id))
                .andExpect(jsonPath("$.status").value("PAID"));

        assertThat(orderRepository.findById(id).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        Map<String, Object> payment = jdbc.queryForMap("SELECT * FROM payment WHERE order_id = ?", id);
        assertThat((String) payment.get("payment_key")).startsWith("SIM-");
        assertThat(payment).containsEntry("status", "DONE").containsEntry("method", "카드");
        Integer confirms = jdbc.queryForObject("SELECT COUNT(*) FROM outbox WHERE message_type = 'CONFIRM' "
                + "AND payload LIKE ?", Integer.class, "%\"orderId\":" + id + "%");
        assertThat(confirms).isEqualTo(1);

        // 다시 불러도 그대로 (결제 기록 1건)
        mockMvc.perform(post("/ops/orders/" + id + "/simulated-payment"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAID"));
        assertThat(jdbc.queryForObject("SELECT COUNT(*) FROM payment WHERE order_id = ?", Integer.class, id))
                .isEqualTo(1);
        verify(toss, never()).confirm(anyString(), anyString(), org.mockito.ArgumentMatchers.anyLong(), anyString());
    }

    @Test
    void SIM_결제를_고객이_취소하면_토스_없이_환불_완료() throws Exception {
        long id = waitingOrder();
        mockMvc.perform(post("/ops/orders/" + id + "/simulated-payment")).andExpect(status().isOk());

        mockMvc.perform(post("/orders/" + id + "/cancel")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"reason\":\"CHANGE_OF_MIND\"}")
                        .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("REFUNDED"))
                .andExpect(jsonPath("$.refundStatus").value("COMPLETED"));

        verify(toss, never()).cancel(anyString(), anyString(), anyString());
    }

    @Test
    void 결제_대기가_아니면_409() throws Exception {
        long id = waitingOrder();
        jdbc.update("UPDATE orders SET status = 'EXPIRED' WHERE id = ?", id);

        mockMvc.perform(post("/ops/orders/" + id + "/simulated-payment"))
                .andExpect(status().isConflict());
    }
}
