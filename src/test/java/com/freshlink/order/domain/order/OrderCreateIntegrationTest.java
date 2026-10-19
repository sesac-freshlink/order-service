package com.freshlink.order.domain.order;

import com.freshlink.common.message.MessageJson;
import com.freshlink.common.message.command.ReserveMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.context.bean.override.mockito.MockitoSpyBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.MvcResult;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.Mockito.doThrow;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** 실제 MySQL(Testcontainers)에서 주문 생성 전체 흐름을 확인한다. SQS는 목이고 발행기는 1시간 뒤에 돌아 실제 전송이 없다 */
@SpringBootTest(properties = "freshlink.messaging.outbox.publish-interval=1h")
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderCreateIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    JdbcTemplate jdbc;
    @Autowired
    OrderService orderService;
    @Value("${app.queue.commands}")
    String commandsQueue;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @MockitoSpyBean
    OutboxWriter outboxWriter;

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    private String body(long storeId, long productId, int quantity) {
        return "{\"storeId\":" + storeId + ",\"storeName\":\"프레시마트 강남점\",\"productId\":" + productId
                + ",\"quantity\":" + quantity + "}";
    }

    private MockHttpServletRequestBuilder req(String customer, String key, String body) {
        return post("/orders").contentType(MediaType.APPLICATION_JSON).content(body)
                .header("Idempotency-Key", key).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER");
    }

    private int orderCount(String customer) {
        return jdbc.queryForObject("SELECT COUNT(*) FROM orders WHERE customer_id = ?", Integer.class, customer);
    }

    private int outboxCount(long orderId) {
        return jdbc.queryForObject(
                "SELECT COUNT(*) FROM outbox WHERE message_type = 'RESERVE' AND payload LIKE ?", Integer.class,
                "%\"orderId\":" + orderId + ",%");
    }

    @Test
    void 새_주문은_201이고_orders와_outbox에_한_건씩_저장된다() throws Exception {
        String customer = uuid();
        String key = uuid();

        MvcResult res = mockMvc.perform(req(customer, key, body(7, 10, 2)))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andReturn();

        Map<String, Object> order = jdbc.queryForMap("SELECT * FROM orders WHERE customer_id = ?", customer);
        long orderId = ((Number) order.get("id")).longValue();
        assertThat(order.get("request_id")).isEqualTo(key);
        assertThat(order.get("status")).isEqualTo("RESERVING");
        assertThat((String) order.get("order_no")).matches("^FL-\\d{8}-\\d{6,}$").endsWith(String.format("%06d", orderId));
        String kstDate = jdbc.queryForObject(
                "SELECT DATE_FORMAT(created_at, '%Y%m%d') FROM orders WHERE id = ?", String.class, orderId);
        assertThat((String) order.get("order_no")).contains("-" + kstDate + "-");
        assertThat(res.getResponse().getContentAsString()).contains("\"orderId\":" + orderId);

        List<Map<String, Object>> outbox = jdbc.queryForList(
                "SELECT * FROM outbox WHERE message_type = 'RESERVE' AND payload LIKE ?",
                "%\"orderId\":" + orderId + ",%");
        assertThat(outbox).hasSize(1);
        Map<String, Object> row = outbox.get(0);
        assertThat(row.get("queue_name")).isEqualTo(commandsQueue);
        assertThat(row.get("group_id")).isEqualTo("7");
        assertThat(jdbc.queryForObject("SELECT published FROM outbox WHERE id = ?", Boolean.class, row.get("id"))).isFalse();
        ReserveMessage msg = MessageJson.fromJson((String) row.get("payload"), ReserveMessage.class);
        assertThat(msg.orderId()).isEqualTo(orderId);
        assertThat(msg.storeId()).isEqualTo(7L);
        assertThat(msg.productId()).isEqualTo(10L);
        assertThat(msg.quantity()).isEqualTo(2);
        verifyNoInteractions(sqsTemplate);
    }

    @Test
    void 같은_키_같은_내용_재요청은_200이고_행이_늘지_않는다() throws Exception {
        String customer = uuid();
        String key = uuid();
        mockMvc.perform(req(customer, key, body(7, 10, 2))).andExpect(status().isCreated());
        long orderId = jdbc.queryForObject("SELECT id FROM orders WHERE customer_id = ?", Long.class, customer);

        mockMvc.perform(req(customer, key.toUpperCase(), body(7, 10, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(orderId));

        assertThat(orderCount(customer)).isEqualTo(1);
        assertThat(outboxCount(orderId)).isEqualTo(1);
    }

    @Test
    void 같은_키_다른_내용은_409() throws Exception {
        String customer = uuid();
        String key = uuid();
        mockMvc.perform(req(customer, key, body(7, 10, 2))).andExpect(status().isCreated());

        mockMvc.perform(req(customer, key, body(7, 10, 3)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("IDEMPOTENCY_KEY_REUSED"));

        assertThat(orderCount(customer)).isEqualTo(1);
    }

    @Test
    void 같은_키라도_고객이_다르면_새_주문() throws Exception {
        String key = uuid();
        mockMvc.perform(req(uuid(), key, body(7, 10, 2))).andExpect(status().isCreated());
        mockMvc.perform(req(uuid(), key, body(7, 10, 2))).andExpect(status().isCreated());
    }

    @Test
    void 재요청하면_현재_상태를_돌려준다() throws Exception {
        String customer = uuid();
        String key = uuid();
        mockMvc.perform(req(customer, key, body(7, 10, 2))).andExpect(status().isCreated());
        jdbc.update("UPDATE orders SET status = 'PAYMENT_WAITING' WHERE customer_id = ?", customer);

        mockMvc.perform(req(customer, key, body(7, 10, 2)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_WAITING"));
    }

    @Test
    void outbox_저장이_실패하면_주문도_저장되지_않는다() throws Exception {
        String customer = uuid();
        doThrow(new RuntimeException("outbox down")).when(outboxWriter).save(any(), any());
        try {
            mockMvc.perform(req(customer, uuid(), body(7, 10, 2)))
                    .andExpect(status().isInternalServerError());
        } finally {
            org.mockito.Mockito.reset(outboxWriter);
        }

        assertThat(orderCount(customer)).isZero();
    }

    @Test
    void 같은_키_동시_요청도_주문은_한_건만_만든다() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                String customer = uuid();
                String key = uuid();
                CreateOrderRequest request = new CreateOrderRequest(7L, "프레시마트 강남점", 10L, 2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<CreateOrderResult>> futures = List.of(
                        pool.submit(() -> { start.await(); return orderService.create(customer, key, request); }),
                        pool.submit(() -> { start.await(); return orderService.create(customer, key, request); }));
                start.countDown();

                CreateOrderResult a = futures.get(0).get();
                CreateOrderResult b = futures.get(1).get();

                assertThat(a.created() ^ b.created()).isTrue();
                assertThat(a.order().getId()).isEqualTo(b.order().getId());
                assertThat(orderCount(customer)).isEqualTo(1);
                assertThat(outboxCount(a.order().getId())).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
