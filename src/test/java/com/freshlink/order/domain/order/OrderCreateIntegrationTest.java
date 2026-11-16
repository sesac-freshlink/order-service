package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.MessageJson;
import com.freshlink.order.common.error.OrderErrorCode;
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
import java.util.concurrent.ExecutionException;
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
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
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

    /** 이 주문의 RESERVE outbox 행. payload는 JSON으로 읽어 비교한다 (필드 순서에 기대지 않는다) */
    private List<Map<String, Object>> reserveRows(long orderId) {
        return jdbc.queryForList("SELECT * FROM outbox WHERE message_type = 'RESERVE'").stream()
                .filter(row -> MessageJson.fromJson((String) row.get("payload"), ReserveMessage.class)
                        .orderId().equals(orderId))
                .toList();
    }

    private int outboxCount(long orderId) {
        return reserveRows(orderId).size();
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

        List<Map<String, Object>> outbox = reserveRows(orderId);
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
    void 마감세일_수량이_전체_수량보다_많으면_400_VALIDATION_ERROR() throws Exception {
        String body = "{\"storeId\":7,\"storeName\":\"프레시마트 강남점\",\"productId\":10,\"quantity\":2,"
                + "\"clearanceQuantity\":3}";
        mockMvc.perform(req(uuid(), uuid(), body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("VALIDATION_ERROR"));
        String negative = "{\"storeId\":7,\"storeName\":\"프레시마트 강남점\",\"productId\":10,\"quantity\":2,"
                + "\"clearanceQuantity\":-1}";
        mockMvc.perform(req(uuid(), uuid(), negative)).andExpect(status().isBadRequest());
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

    // ---------------------------------------------------------------- 열린 주문 한 건 (V10, 11/15)

    private void setStatus(String customer, String status) {
        jdbc.update("UPDATE orders SET status = ? WHERE customer_id = ?", status, customer);
    }

    @Test
    void 같은_고객_점포_상품에_결제하지_않은_주문이_있으면_다른_키라도_409_ORDER_ALREADY_PENDING() throws Exception {
        String customer = uuid();
        mockMvc.perform(req(customer, uuid(), body(7, 10, 2))).andExpect(status().isCreated());
        // 수량이 달라도 막는다(P4)
        mockMvc.perform(req(customer, uuid(), body(7, 10, 1)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PENDING"));
        setStatus(customer, "PAYMENT_WAITING");
        mockMvc.perform(req(customer, uuid(), body(7, 10, 2)))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("ORDER_ALREADY_PENDING"));
        assertThat(orderCount(customer)).isEqualTo(1);
    }

    @Test
    void 앞_주문이_끝났거나_승인_중이면_다시_만들_수_있다() throws Exception {
        for (String closed : new String[] {"EXPIRED", "CANCELED", "OUT_OF_STOCK", "PAYMENT_APPROVING", "PAID"}) {
            String customer = uuid();
            mockMvc.perform(req(customer, uuid(), body(7, 10, 2))).andExpect(status().isCreated());
            setStatus(customer, closed);
            mockMvc.perform(req(customer, uuid(), body(7, 10, 2))).andExpect(status().isCreated());
            assertThat(orderCount(customer)).isEqualTo(2);
        }
    }

    @Test
    void 다른_상품이나_다른_점포나_다른_고객은_막지_않는다() throws Exception {
        String customer = uuid();
        mockMvc.perform(req(customer, uuid(), body(7, 10, 2))).andExpect(status().isCreated());
        mockMvc.perform(req(customer, uuid(), body(7, 11, 2))).andExpect(status().isCreated());
        mockMvc.perform(req(customer, uuid(), body(8, 10, 2))).andExpect(status().isCreated());
        mockMvc.perform(req(uuid(), uuid(), body(7, 10, 2))).andExpect(status().isCreated());
        assertThat(orderCount(customer)).isEqualTo(3);
    }

    @Test
    void 두_탭에서_다른_키로_동시에_눌러도_주문은_한_건만_생기고_나머지는_ORDER_ALREADY_PENDING() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(2);
        try {
            for (int i = 0; i < 10; i++) {
                String customer = uuid();
                CreateOrderRequest request = new CreateOrderRequest(7L, "프레시마트 강남점", 10L, 2);
                CountDownLatch start = new CountDownLatch(1);
                List<Future<CreateOrderResult>> futures = List.of(
                        pool.submit(() -> { start.await(); return orderService.create(customer, uuid(), request); }),
                        pool.submit(() -> { start.await(); return orderService.create(customer, uuid(), request); }));
                start.countDown();

                int created = 0;
                int pending = 0;
                for (Future<CreateOrderResult> f : futures) {
                    try {
                        if (f.get().created()) created++;
                    } catch (ExecutionException e) {
                        assertThat(e.getCause()).isInstanceOf(BusinessException.class);
                        assertThat(((BusinessException) e.getCause()).errorCode())
                                .isEqualTo(OrderErrorCode.ORDER_ALREADY_PENDING);
                        pending++;
                    }
                }
                assertThat(created).isEqualTo(1);
                assertThat(pending).isEqualTo(1);
                assertThat(orderCount(customer)).isEqualTo(1);
            }
        } finally {
            pool.shutdownNow();
        }
    }
}
