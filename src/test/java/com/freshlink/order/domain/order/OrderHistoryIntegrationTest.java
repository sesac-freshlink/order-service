package com.freshlink.order.domain.order;

import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
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
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** A3 고객 주문 내역·A6 점주 주문 목록 구분을 실제 MySQL에서 확인한다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderHistoryIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime BASE = OffsetDateTime.of(2026, 10, 20, 10, 0, 0, 0, KST);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;

    /** 테스트마다 새 점포 번호를 써서 다른 테스트 데이터와 섞이지 않게 한다 */
    private long storeId() {
        return 3_000_000L + (System.nanoTime() % 1_000_000L);
    }

    /** 상태·시각을 SQL로 맞춘 주문. minutes는 BASE에서 몇 분 뒤에 만들었는지 (클수록 최신) */
    private long order(String customer, long storeId, String status, int minutes) {
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(), storeId,
                "프레시 강남점", 15L, 2));
        OffsetDateTime at = BASE.plusMinutes(minutes);
        jdbc.update("UPDATE orders SET status = ?, created_at = ?, product_name = '매운 컵라면 115g', unit_price = 1700, "
                        + "amount = 3400, order_no = ?, paid_at = ?, picked_up_at = ?, pickup_deadline = ? WHERE id = ?",
                status, at.toLocalDateTime(), "FL-20261020-" + o.getId(),
                status.equals("PAID") || status.startsWith("PICK") ? at.plusMinutes(1).toLocalDateTime() : null,
                status.equals("PICKED_UP") ? at.plusMinutes(2).toLocalDateTime() : null,
                at.plusDays(2).toLocalDateTime(), o.getId());
        return o.getId();
    }

    private ResultActions customerList(String customer, String query) throws Exception {
        return mockMvc.perform(get("/orders" + query)
                .header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"));
    }

    private MockHttpServletRequestBuilder ownerList(long storeId, String query) {
        return get("/owner/orders" + query).header("X-User-Id", UUID.randomUUID().toString())
                .header("X-User-Role", "OWNER").header("X-Store-Id", String.valueOf(storeId));
    }

    // ---------------------------------------------------------------- A3 고객 주문 내역

    @Test
    void 고객_전체는_본인_주문만_최신순이고_재고부족_결제실패는_빠진다() throws Exception {
        String me = UUID.randomUUID().toString();
        long store = storeId();
        long paid = order(me, store, "PAID", 1);
        long picked = order(me, store, "PICKED_UP", 2);
        long expired = order(me, store, "EXPIRED", 3);
        long pickupExpired = order(me, store, "PICKUP_EXPIRED", 4);
        long waiting = order(me, store, "PAYMENT_WAITING", 5);
        order(me, store, "OUT_OF_STOCK", 6);
        order(me, store, "PAYMENT_FAILED", 7);
        order(UUID.randomUUID().toString(), store, "PAID", 8);   // 다른 고객

        customerList(me, "")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orderId").value(contains(
                        (int) waiting, (int) pickupExpired, (int) expired, (int) picked, (int) paid)))
                .andExpect(jsonPath("$.totalElements").value(5))
                .andExpect(jsonPath("$.page").value(0))
                .andExpect(jsonPath("$.size").value(20))
                .andExpect(jsonPath("$.content[0].storeName").value("프레시 강남점"))
                .andExpect(jsonPath("$.content[0].createdAt").value("2026-10-20T10:05:00+09:00"))
                .andExpect(jsonPath("$.content[0].customerId").doesNotExist());
    }

    @Test
    void 고객_구분별로_나뉘고_기한_경과는_전체에만_나온다() throws Exception {
        String me = UUID.randomUUID().toString();
        long store = storeId();
        long paid = order(me, store, "PAID", 1);
        long picked = order(me, store, "PICKED_UP", 2);
        long expired = order(me, store, "EXPIRED", 3);
        order(me, store, "PICKUP_EXPIRED", 4);

        customerList(me, "?tab=PICKUP_WAITING").andExpect(jsonPath("$.content[*].orderId").value(contains((int) paid)));
        customerList(me, "?tab=PICKED_UP").andExpect(jsonPath("$.content[*].orderId").value(contains((int) picked)));
        customerList(me, "?tab=CANCELED").andExpect(jsonPath("$.content[*].orderId").value(contains((int) expired)));
    }

    @Test
    void 고객_페이지는_10_20_50개씩이고_그_밖은_400() throws Exception {
        String me = UUID.randomUUID().toString();
        long store = storeId();
        for (int i = 0; i < 12; i++) {
            order(me, store, "PAID", i);
        }

        customerList(me, "?tab=PICKUP_WAITING&page=1&size=10")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(2))
                .andExpect(jsonPath("$.totalElements").value(12))
                .andExpect(jsonPath("$.totalPages").value(2));
        customerList(me, "?size=30")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("size: 10, 20, 50 중 하나여야 합니다"));
        customerList(me, "?page=-1").andExpect(status().isBadRequest());
        customerList(me, "?tab=REFUNDED")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("tab: 알 수 없는 구분입니다"));
    }

    @Test
    void 고객이_아니면_403() throws Exception {
        mockMvc.perform(get("/orders").header("X-User-Id", UUID.randomUUID().toString())
                        .header("X-User-Role", "OWNER"))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }

    /** BASE(10/20 10:00 KST)에서 10/21 23:59, 10/22 00:00까지의 분 */
    private static final int OCT21_2359 = 1440 + 839;
    private static final int OCT22_0000 = 1440 + 840;

    @Test
    void 고객_기간은_KST_날짜로_양끝을_포함하고_탭과_같이_쓴다() throws Exception {
        String me = UUID.randomUUID().toString();
        long store = storeId();
        long oct20 = order(me, store, "PAID", 0);
        long oct21Picked = order(me, store, "PICKED_UP", 1440);
        long oct21Late = order(me, store, "PAID", OCT21_2359);
        long oct22First = order(me, store, "PAID", OCT22_0000);
        long oct23 = order(me, store, "PAID", 4320);

        customerList(me, "?from=2026-10-21&to=2026-10-21")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) oct21Late, (int) oct21Picked)));
        customerList(me, "?tab=PICKUP_WAITING&from=2026-10-21&to=2026-10-22")
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) oct22First, (int) oct21Late)));
        customerList(me, "?from=2026-10-22")
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) oct23, (int) oct22First)));
        customerList(me, "?to=2026-10-20")
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) oct20)))
                .andExpect(jsonPath("$.totalElements").value(1));
    }

    @Test
    void 기간이_거꾸로거나_날짜_형식이_틀리면_400() throws Exception {
        String me = UUID.randomUUID().toString();
        customerList(me, "?from=2026-10-22&to=2026-10-21")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"))
                .andExpect(jsonPath("$.message").value("from: to보다 늦을 수 없습니다"));
        customerList(me, "?to=2026/10/21")
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.message").value("to: yyyy-MM-dd 형식이어야 합니다"));
        mockMvc.perform(ownerList(storeId(), "?tab=PICKUP_WAITING&from=2026-10-30&to=2026-10-29"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
    }

    // ---------------------------------------------------------------- A6 점주 주문 목록

    @Test
    void 점주_구분별로_나뉘고_정렬_기준이_다르다() throws Exception {
        long store = storeId();
        String c = UUID.randomUUID().toString();
        long paidOld = order(c, store, "PAID", 1);
        long paidNew = order(c, store, "PAID", 5);
        long pickedOld = order(c, store, "PICKED_UP", 2);
        long pickedNew = order(c, store, "PICKED_UP", 6);
        long pickupExpired = order(c, store, "PICKUP_EXPIRED", 3);
        order(c, store, "EXPIRED", 4);
        order(c, storeId() + 1, "PAID", 7);   // 다른 점포

        mockMvc.perform(ownerList(store, "?tab=PICKUP_WAITING"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) paidNew, (int) paidOld)))
                .andExpect(jsonPath("$.content[0].status").value("PAID"));
        mockMvc.perform(ownerList(store, "?tab=PICKED_UP"))
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) pickedNew, (int) pickedOld)))
                .andExpect(jsonPath("$.content[0].pickedUpAt").value("2026-10-20T10:08:00+09:00"));
        mockMvc.perform(ownerList(store, "?tab=PICKUP_EXPIRED"))
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) pickupExpired)));
        // 취소·환불은 환불(A7~A9) 전이라 비어 있다. 결제 전 만료(EXPIRED)는 점주 목록에 넣지 않는다
        mockMvc.perform(ownerList(store, "?tab=CANCELED"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content.length()").value(0))
                .andExpect(jsonPath("$.totalElements").value(0));
    }

    @Test
    void 점주_tab이_없으면_MVP처럼_결제_완료_배열이다() throws Exception {
        long store = storeId();
        long paid = order(UUID.randomUUID().toString(), store, "PAID", 1);
        order(UUID.randomUUID().toString(), store, "PICKED_UP", 2);

        mockMvc.perform(ownerList(store, ""))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()").value(1))
                .andExpect(jsonPath("$[0].orderId").value((int) paid));
    }

    @Test
    void 점주_모르는_구분이나_잘못된_페이지는_400() throws Exception {
        long store = storeId();
        mockMvc.perform(ownerList(store, "?tab=ALL"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        mockMvc.perform(ownerList(store, "?tab=PICKED_UP&size=0")).andExpect(status().isBadRequest());
    }

    @Test
    void 점주_기간은_주문일시_기준이고_탭과_같이_쓴다() throws Exception {
        long store = storeId();
        String c = UUID.randomUUID().toString();
        order(c, store, "PAID", 0);
        long late = order(c, store, "PAID", OCT21_2359);
        long next = order(c, store, "PAID", OCT22_0000);
        order(c, store, "PICKED_UP", OCT22_0000 + 1);

        mockMvc.perform(ownerList(store, "?tab=PICKUP_WAITING&from=2026-10-22&to=2026-10-22"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) next)));
        mockMvc.perform(ownerList(store, "?tab=PICKUP_WAITING&from=2026-10-21"))
                .andExpect(jsonPath("$.content[*].orderId").value(contains((int) next, (int) late)));
    }
}
