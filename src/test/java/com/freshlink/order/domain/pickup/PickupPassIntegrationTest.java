package com.freshlink.order.domain.pickup;

import com.freshlink.order.RedisTestcontainersConfiguration;
import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
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
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.convention.TestBean;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 픽업 QR (추가 명세 A16, OR-07~OR-11)을 실제 MySQL·Valkey에서 확인한다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false",
        "spring.data.redis.ssl.enabled=false"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class PickupPassIntegrationTest {

    private static final ZoneOffset KST = ZoneOffset.ofHours(9);
    private static final OffsetDateTime NOW = OffsetDateTime.of(2026, 11, 6, 15, 0, 0, 0, KST);
    private static final OffsetDateTime TODAY_END = OffsetDateTime.of(2026, 11, 6, 23, 59, 59, 0, KST);

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    PickupScanStream stream;
    @Autowired
    ObjectMapper json;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @TestBean
    Clock clock;

    static Clock clock() {
        return Clock.fixed(Instant.from(NOW), ZoneId.of("Asia/Seoul"));
    }

    private long storeId() {
        return 3_000_000L + (System.nanoTime() % 1_000_000L);
    }

    /** 상태·기한을 SQL로 맞춘 주문 */
    private long order(String customer, long storeId, String status, OffsetDateTime deadline) {
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(), storeId, "점포", 15L, 1));
        jdbc.update("UPDATE orders SET status = ?, pickup_deadline = ?, product_name = '매운 컵라면 115g', "
                        + "unit_price = 1700, amount = 1700, order_no = ?, paid_at = ? WHERE id = ?",
                status, deadline.toLocalDateTime(), "FL-20261106-" + o.getId(),
                NOW.minusHours(1).toLocalDateTime(), o.getId());
        return o.getId();
    }

    private ResultActions issue(String customer) throws Exception {
        return mockMvc.perform(post("/orders/pickup-pass").header("X-User-Id", customer).header("X-User-Role", "CUSTOMER"));
    }

    private JsonNode issued(String customer) throws Exception {
        return json.readTree(issue(customer).andExpect(status().isOk()).andReturn().getResponse().getContentAsString());
    }

    private ResultActions owner(org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder req, long store)
            throws Exception {
        return mockMvc.perform(req.header("X-User-Id", UUID.randomUUID().toString()).header("X-User-Role", "OWNER")
                .header("X-Store-Id", String.valueOf(store)));
    }

    private ResultActions list(long store, String key) throws Exception {
        return owner(get("/owner/orders/pickup-pass/" + key), store);
    }

    private ResultActions approve(long store, String token, String ids) throws Exception {
        return owner(post("/owner/orders/pickup").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\",\"orderIds\":" + ids + "}"), store);
    }

    // ---------------------------------------------------------------- OR-07 고객 QR

    @Test
    void 미픽업_주문이_있으면_표와_6자리_코드를_5분짜리로_만든다() throws Exception {
        String customer = UUID.randomUUID().toString();
        order(customer, storeId(), "PAID", TODAY_END);
        order(customer, storeId(), "READY_FOR_PICKUP", TODAY_END);

        JsonNode pass = issued(customer);
        assertThat(pass.get("token").asText()).hasSize(32);
        assertThat(pass.get("code").asText()).matches("\\d{6}");
        assertThat(pass.get("expiresAt").asText()).isEqualTo("2026-11-06T15:05:00+09:00");
        assertThat(pass.get("orderCount").asInt()).isEqualTo(2);
        Long ttl = redis.getExpire(PickupPassStore.passKey(pass.get("token").asText()));
        assertThat(ttl).isBetween(250L, 300L);
    }

    @Test
    void 미픽업_주문이_없거나_기한이_지났으면_409_고객이_아니면_403() throws Exception {
        String customer = UUID.randomUUID().toString();
        order(customer, storeId(), "PICKED_UP", TODAY_END);
        order(customer, storeId(), "READY_FOR_PICKUP", NOW.minusDays(1));
        issue(customer).andExpect(status().isConflict()).andExpect(jsonPath("$.code").value("NO_PICKUP_WAITING_ORDER"));
        mockMvc.perform(post("/orders/pickup-pass").header("X-User-Id", customer).header("X-User-Role", "OWNER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 새_QR을_만들면_예전_표와_코드는_못_쓴다() throws Exception {
        String customer = UUID.randomUUID().toString();
        long store = storeId();
        order(customer, store, "READY_FOR_PICKUP", TODAY_END);
        JsonNode first = issued(customer);
        JsonNode second = issued(customer);

        list(store, first.get("token").asText()).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PICKUP_PASS_NOT_FOUND"));
        list(store, first.get("code").asText()).andExpect(status().isNotFound());
        list(store, second.get("token").asText()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- OR-08·09 스캔·SSE

    @Test
    void 핸드폰_스캔은_이_점포의_열린_화면_수를_알려_주고_없는_표는_404() throws Exception {
        String customer = UUID.randomUUID().toString();
        long store = storeId();
        order(customer, store, "READY_FOR_PICKUP", TODAY_END);
        String token = issued(customer).get("token").asText();

        owner(post("/owner/orders/pickup-scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"), store)
                .andExpect(status().isOk()).andExpect(jsonPath("$.screens").value(0));

        stream.subscribe(store);
        stream.subscribe(store);
        owner(post("/owner/orders/pickup-scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"" + token + "\"}"), store)
                .andExpect(status().isOk()).andExpect(jsonPath("$.screens").value(2));

        owner(post("/owner/orders/pickup-scan").contentType(MediaType.APPLICATION_JSON)
                .content("{\"token\":\"nope\"}"), store)
                .andExpect(status().isNotFound()).andExpect(jsonPath("$.code").value("PICKUP_PASS_NOT_FOUND"));
    }

    @Test
    void 알림_SSE는_점주만_아니면_본문_없이_403() throws Exception {
        mockMvc.perform(get("/owner/orders/pickup-stream").header("X-User-Role", "CUSTOMER").header("X-Store-Id", "1"))
                .andExpect(status().isForbidden());
        owner(get("/owner/orders/pickup-stream"), storeId()).andExpect(status().isOk());
    }

    // ---------------------------------------------------------------- OR-10 목록

    @Test
    void 목록은_그_고객의_이_점포_미픽업_주문만이고_준비_완료만_고를_수_있다() throws Exception {
        String customer = UUID.randomUUID().toString();
        long store = storeId();
        long paid = order(customer, store, "PAID", TODAY_END);
        long ready = order(customer, store, "READY_FOR_PICKUP", TODAY_END);
        order(customer, store, "PICKED_UP", TODAY_END);
        order(customer, store, "READY_FOR_PICKUP", NOW.minusDays(1));
        order(customer, store + 1, "READY_FOR_PICKUP", TODAY_END);
        order(UUID.randomUUID().toString(), store, "READY_FOR_PICKUP", TODAY_END);
        JsonNode pass = issued(customer);

        for (String key : new String[] {pass.get("token").asText(), pass.get("code").asText()}) {
            list(store, key).andExpect(status().isOk())
                    .andExpect(jsonPath("$.token").value(pass.get("token").asText()))
                    .andExpect(jsonPath("$.orders.length()").value(2))
                    .andExpect(jsonPath("$.orders[0].orderId").value(paid))
                    .andExpect(jsonPath("$.orders[0].selectable").value(false))
                    .andExpect(jsonPath("$.orders[1].orderId").value(ready))
                    .andExpect(jsonPath("$.orders[1].selectable").value(true))
                    .andExpect(jsonPath("$.orders[1].productName").value("매운 컵라면 115g"));
        }
    }

    @Test
    void 코드를_1분에_10번_틀리면_429() throws Exception {
        long store = storeId();
        for (int i = 0; i < 10; i++) {
            list(store, "000000").andExpect(status().isNotFound());
        }
        list(store, "000000").andExpect(status().isTooManyRequests())
                .andExpect(jsonPath("$.code").value("PICKUP_CODE_TOO_MANY_ATTEMPTS"));
        list(store + 1, "000000").andExpect(status().isNotFound());
    }

    // ---------------------------------------------------------------- OR-11 일괄 승인

    @Test
    void 고른_준비_완료_주문만_픽업되고_건마다_결과가_온다() throws Exception {
        String customer = UUID.randomUUID().toString();
        long store = storeId();
        long ready1 = order(customer, store, "READY_FOR_PICKUP", TODAY_END);
        long ready2 = order(customer, store, "READY_FOR_PICKUP", TODAY_END);
        long paid = order(customer, store, "PAID", TODAY_END);
        long late = order(customer, store, "READY_FOR_PICKUP", NOW.minusDays(1));
        long otherStore = order(customer, store + 1, "READY_FOR_PICKUP", TODAY_END);
        long otherCustomer = order(UUID.randomUUID().toString(), store, "READY_FOR_PICKUP", TODAY_END);
        String token = issued(customer).get("token").asText();

        approve(store, token, "[" + ready1 + "," + ready2 + "," + paid + "," + late + "," + otherStore + ","
                + otherCustomer + "," + ready1 + "]")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.results.length()").value(6))
                .andExpect(jsonPath("$.results[0].result").value("PICKED_UP"))
                .andExpect(jsonPath("$.results[1].result").value("PICKED_UP"))
                .andExpect(jsonPath("$.results[2].result").value("NOT_PICKABLE"))
                .andExpect(jsonPath("$.results[3].result").value("DEADLINE_PASSED"))
                .andExpect(jsonPath("$.results[4].result").value("NOT_FOUND"))
                .andExpect(jsonPath("$.results[5].result").value("NOT_FOUND"));

        assertThat(orderRepository.findById(ready1).orElseThrow().getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(orderRepository.findById(ready2).orElseThrow().getStatus()).isEqualTo(OrderStatus.PICKED_UP);
        assertThat(orderRepository.findById(paid).orElseThrow().getStatus()).isEqualTo(OrderStatus.PAID);
        assertThat(orderRepository.findById(otherStore).orElseThrow().getStatus()).isEqualTo(OrderStatus.READY_FOR_PICKUP);
        assertThat(orderRepository.findById(otherCustomer).orElseThrow().getStatus())
                .isEqualTo(OrderStatus.READY_FOR_PICKUP);
    }

    @Test
    void 승인은_1건에서_20건까지이고_만료된_표는_404_점주가_아니면_403() throws Exception {
        String customer = UUID.randomUUID().toString();
        long store = storeId();
        order(customer, store, "READY_FOR_PICKUP", TODAY_END);
        String token = issued(customer).get("token").asText();
        StringBuilder many = new StringBuilder("[");
        for (int i = 1; i <= 21; i++) {
            many.append(i).append(i < 21 ? "," : "]");
        }

        approve(store, token, "[]").andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        approve(store, token, many.toString()).andExpect(status().isBadRequest());
        approve(store, "expired", "[1]").andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("PICKUP_PASS_NOT_FOUND"));
        mockMvc.perform(post("/owner/orders/pickup").header("X-User-Role", "CUSTOMER").header("X-Store-Id", "1")
                        .contentType(MediaType.APPLICATION_JSON).content("{\"token\":\"" + token + "\",\"orderIds\":[1]}"))
                .andExpect(status().isForbidden()).andExpect(jsonPath("$.code").value("FORBIDDEN"));
    }
}
