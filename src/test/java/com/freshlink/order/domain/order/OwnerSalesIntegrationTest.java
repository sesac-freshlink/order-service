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
import org.springframework.test.web.servlet.ResultActions;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 점주 매출 API를 실제 MySQL에서 확인한다. 점포는 테스트마다 새 번호를 써서 다른 테스트 데이터와 섞이지 않게 한다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OwnerSalesIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;

    private long storeId() {
        return 2_000_000L + (System.nanoTime() % 1_000_000L);
    }

    /** 결제 기록이 있는 주문을 SQL로 만든다. approvedAt은 KST 시각 문자열 */
    private void paid(long storeId, String orderStatus, String paymentStatus, long amount, String approvedAt) {
        Order o = orderRepository.saveAndFlush(Order.create(UUID.randomUUID().toString(),
                UUID.randomUUID().toString(), storeId, "점포", 15L, 1));
        jdbc.update("UPDATE orders SET status = ?, paid_at = ?, product_name = '매운 컵라면 115g', unit_price = ?, "
                + "amount = ?, order_no = ? WHERE id = ?", orderStatus, approvedAt, amount, amount,
                "FL-20261020-" + o.getId(), o.getId());
        jdbc.update("INSERT INTO payment (order_id, payment_key, toss_idempotency_key, amount, status, toss_status, "
                        + "method, approved_at, created_at, updated_at) VALUES (?, ?, ?, ?, ?, ?, '카드', ?, NOW(6), NOW(6))",
                o.getId(), "pk_" + o.getId(), UUID.randomUUID().toString(), amount, paymentStatus, paymentStatus,
                approvedAt);
    }

    private ResultActions sales(long storeId, String query) throws Exception {
        return mockMvc.perform(get("/owner/orders/sales?" + query)
                .header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(storeId)));
    }

    @Test
    void 내_점포의_승인_완료_결제만_KST_날짜별로_더한다() throws Exception {
        long store = storeId();
        paid(store, "PAID", "DONE", 3400L, "2026-10-20 23:59:59");
        paid(store, "PICKED_UP", "DONE", 1700L, "2026-10-20 10:00:00");
        paid(store, "PAID", "DONE", 1500L, "2026-10-21 00:00:00");
        paid(store, "PAYMENT_APPROVING", "APPROVING", 9999L, "2026-10-21 09:00:00");
        paid(store, "PAYMENT_FAILED", "FAILED", 8888L, "2026-10-21 09:00:00");
        paid(store + 1, "PAID", "DONE", 7777L, "2026-10-21 09:00:00");

        sales(store, "from=2026-10-20&to=2026-10-22")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.from").value("2026-10-20"))
                .andExpect(jsonPath("$.to").value("2026-10-22"))
                .andExpect(jsonPath("$.unit").value("day"))
                .andExpect(jsonPath("$.periods.length()").value(3))
                .andExpect(jsonPath("$.periods[0].period").value("2026-10-20"))
                .andExpect(jsonPath("$.periods[0].paidAmount").value(5100))
                .andExpect(jsonPath("$.periods[0].paidCount").value(2))
                .andExpect(jsonPath("$.periods[0].netAmount").value(5100))
                .andExpect(jsonPath("$.periods[0].refundAmount").value(0))
                .andExpect(jsonPath("$.periods[0].refundCount").value(0))
                .andExpect(jsonPath("$.periods[0].canceledCount").value(0))
                .andExpect(jsonPath("$.periods[1].period").value("2026-10-21"))
                .andExpect(jsonPath("$.periods[1].paidAmount").value(1500))
                .andExpect(jsonPath("$.periods[1].paidCount").value(1))
                .andExpect(jsonPath("$.periods[2].period").value("2026-10-22"))
                .andExpect(jsonPath("$.periods[2].paidAmount").value(0))
                .andExpect(jsonPath("$.periods[2].paidCount").value(0));
    }

    @Test
    void 기간_밖의_결제는_빠진다() throws Exception {
        long store = storeId();
        paid(store, "PAID", "DONE", 1000L, "2026-10-19 23:59:59");
        paid(store, "PAID", "DONE", 2000L, "2026-10-20 00:00:00");
        paid(store, "PAID", "DONE", 4000L, "2026-10-21 00:00:00");

        sales(store, "from=2026-10-20&to=2026-10-20")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods.length()").value(1))
                .andExpect(jsonPath("$.periods[0].paidAmount").value(2000))
                .andExpect(jsonPath("$.periods[0].paidCount").value(1));
    }

    @Test
    void 주_단위는_월요일_시작_월_단위는_yyyy_MM() throws Exception {
        long store = storeId();
        paid(store, "PAID", "DONE", 1000L, "2026-10-25 12:00:00"); // 일요일 → 10/19 주
        paid(store, "PAID", "DONE", 2000L, "2026-10-26 12:00:00"); // 월요일 → 10/26 주
        paid(store, "PAID", "DONE", 3000L, "2026-11-01 12:00:00"); // 일요일 → 10/26 주, 11월

        sales(store, "from=2026-10-25&to=2026-11-01&unit=week")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.unit").value("week"))
                .andExpect(jsonPath("$.periods.length()").value(2))
                .andExpect(jsonPath("$.periods[0].period").value("2026-10-19"))
                .andExpect(jsonPath("$.periods[0].paidAmount").value(1000))
                .andExpect(jsonPath("$.periods[1].period").value("2026-10-26"))
                .andExpect(jsonPath("$.periods[1].paidAmount").value(5000))
                .andExpect(jsonPath("$.periods[1].paidCount").value(2));

        sales(store, "from=2026-10-25&to=2026-11-01&unit=month")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.periods.length()").value(2))
                .andExpect(jsonPath("$.periods[0].period").value("2026-10"))
                .andExpect(jsonPath("$.periods[0].paidAmount").value(3000))
                .andExpect(jsonPath("$.periods[1].period").value("2026-11"))
                .andExpect(jsonPath("$.periods[1].paidAmount").value(3000));
    }

    @Test
    void 기간_단위가_잘못되면_400_VALIDATION_ERROR() throws Exception {
        long store = storeId();
        sales(store, "to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        sales(store, "from=2026-10-21&to=2026-10-20").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        sales(store, "from=2026-10-20&to=2026-10-20&unit=year").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    @Test
    void 점주가_아니면_403() throws Exception {
        mockMvc.perform(get("/owner/orders/sales?from=2026-10-20&to=2026-10-20")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "CUSTOMER").header("X-Store-Id", "1"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 연결된_점포가_없으면_403() throws Exception {
        mockMvc.perform(get("/owner/orders/sales?from=2026-10-20&to=2026-10-20")
                        .header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("STORE_NOT_LINKED"));
    }
}
