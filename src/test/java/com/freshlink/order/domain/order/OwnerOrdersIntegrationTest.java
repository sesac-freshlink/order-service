package com.freshlink.order.domain.order;

import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OR-04 점주 신규 주문 목록을 실제 MySQL에서 확인한다. 점포는 테스트마다 새 번호를 써서 다른 테스트 데이터와 섞이지 않게 한다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnerOrdersIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;

    private long storeId() {
        return 1_000_000L + (System.nanoTime() % 1_000_000L);
    }

    /** 주문을 만들고 상태를 SQL로 바꾼다 (결제 완료 처리는 OR-03에서 생긴다) */
    private long order(long storeId, String status, String paidAt, Long amount) {
        Order o = orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), storeId, "점포", 15L, 2));
        jdbc.update("UPDATE orders SET status = ?, paid_at = ?, product_name = '매운 컵라면 115g', unit_price = 1700, "
                + "amount = ?, order_no = ? WHERE id = ?", status, paidAt, amount, "FL-20261020-" + o.getId(), o.getId());
        return o.getId();
    }

    @Test
    void 내_점포의_PAID_주문만_결제_최신순으로_내린다() throws Exception {
        long store = storeId();
        long older = order(store, "PAID", "2026-10-20 15:00:00", 3400L);
        long newer = order(store, "PAID", "2026-10-20 15:35:10", 3400L);
        order(store, "PAYMENT_WAITING", null, 3400L);
        order(store, "RESERVING", null, null);
        order(store, "EXPIRED", null, 3400L);
        order(store + 1, "PAID", "2026-10-20 16:00:00", 3400L);

        mockMvc.perform(get("/owner/orders").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(2))
                .andExpect(jsonPath("$[0].orderId").value(newer))
                .andExpect(jsonPath("$[0].paidAt").value("2026-10-20T15:35:10+09:00"))
                .andExpect(jsonPath("$[0].amount").value(3400))
                .andExpect(jsonPath("$[1].orderId").value(older));
    }

    @Test
    void 목록의_각_주문에_결제_내역이_붙는다() throws Exception {
        long store = storeId();
        long withReceipt = order(store, "PAID", "2026-10-20 15:35:10", 3400L);
        long withoutReceipt = order(store, "PAID", "2026-10-20 15:00:00", 3400L);
        payment(withReceipt, "https://receipt.test/1");
        payment(withoutReceipt, null);

        mockMvc.perform(get("/owner/orders").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(store)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].orderId").value(withReceipt))
                .andExpect(jsonPath("$[0].payment.method").value("카드"))
                .andExpect(jsonPath("$[0].payment.approvedAt").value("2026-10-20T15:35:10+09:00"))
                .andExpect(jsonPath("$[0].payment.status").value("DONE"))
                .andExpect(jsonPath("$[0].payment.receiptUrl").value("https://receipt.test/1"))
                .andExpect(jsonPath("$[1].orderId").value(withoutReceipt))
                .andExpect(jsonPath("$[1].payment.status").value("DONE"))
                .andExpect(jsonPath("$[1].payment.receiptUrl").isEmpty());
    }

    /** OR-03이 남기는 승인 완료 결제 기록을 SQL로 넣는다. 승인 시각은 결제 완료 시각과 같다 */
    private void payment(long orderId, String receiptUrl) {
        jdbc.update("INSERT INTO payment (order_id, payment_key, toss_idempotency_key, amount, status, toss_status, "
                        + "method, approved_at, receipt_url, created_at, updated_at) "
                        + "SELECT id, ?, ?, amount, 'DONE', 'DONE', '카드', paid_at, ?, NOW(6), NOW(6) FROM orders WHERE id = ?",
                "pk_" + orderId, UUID.randomUUID().toString(), receiptUrl, orderId);
    }

    @Test
    void 결제된_주문이_없으면_빈_배열() throws Exception {
        mockMvc.perform(get("/owner/orders").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(storeId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(0));
    }
}
