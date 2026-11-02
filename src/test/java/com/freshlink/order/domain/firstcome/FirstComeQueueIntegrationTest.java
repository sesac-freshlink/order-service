package com.freshlink.order.domain.firstcome;

import com.freshlink.order.RedisTestcontainersConfiguration;
import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.Clock;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;
import net.javacrumbs.shedlock.core.LockProvider;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.anyLong;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * 선착순 대기열 전체 흐름: 실제 MySQL·Valkey(Testcontainers), inventory 남은 수량은 목.
 * 스케줄러 빈은 끄고(app.expiry.enabled=false) 입장 처리는 테스트가 직접 한 번씩 돌린다
 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false",
        "spring.data.redis.ssl.enabled=false", "app.first-come.remaining-cache-ttl=0s"})
@AutoConfigureMockMvc
@Import({TestcontainersConfiguration.class, RedisTestcontainersConfiguration.class})
class FirstComeQueueIntegrationTest {

    private static final long STORE = 1L;

    @Autowired
    MockMvc mockMvc;
    @Autowired
    StringRedisTemplate redis;
    @Autowired
    FirstComeService firstComeService;
    @Autowired
    FirstComeQueue queue;
    @Autowired
    FirstComeProperties properties;
    @Autowired
    LockProvider lockProvider;
    @Autowired
    Clock clock;
    @Autowired
    JdbcTemplate jdbc;

    @MockitoBean
    SqsTemplate sqsTemplate;
    @MockitoBean
    InventoryFirstComeClient inventory;

    private FirstComeScheduler scheduler;

    @BeforeEach
    void setUp() {
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        scheduler = new FirstComeScheduler(firstComeService, queue, properties, lockProvider, clock);
    }

    private static String user() {
        return UUID.randomUUID().toString();
    }

    private void remaining(long productId, int remaining) {
        when(inventory.lookup(STORE, productId)).thenReturn(FirstComeLookup.firstCome(10, remaining));
    }

    private MockHttpServletRequestBuilder join(String user, long productId) {
        return post("/orders/queue/" + productId).param("storeId", String.valueOf(STORE))
                .header("X-User-Id", user).header("X-User-Role", "CUSTOMER");
    }

    private MockHttpServletRequestBuilder me(String user, long productId) {
        return get("/orders/queue/" + productId + "/me").param("storeId", String.valueOf(STORE))
                .header("X-User-Id", user).header("X-User-Role", "CUSTOMER");
    }

    private MockHttpServletRequestBuilder order(String user, long productId, int quantity) {
        return post("/orders").contentType(MediaType.APPLICATION_JSON)
                .content("{\"storeId\":" + STORE + ",\"storeName\":\"프레시 강남점\",\"productId\":" + productId
                        + ",\"quantity\":" + quantity + "}")
                .header("Idempotency-Key", UUID.randomUUID().toString())
                .header("X-User-Id", user).header("X-User-Role", "CUSTOMER");
    }

    @Test
    void 줄을_서면_들어온_순서대로_순번과_앞사람_수_남은_수량을_준다() throws Exception {
        remaining(101, 3);
        String first = user();
        String second = user();
        mockMvc.perform(join(first, 101)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1))
                .andExpect(jsonPath("$.ahead").value(0))
                .andExpect(jsonPath("$.remaining").value(3));
        mockMvc.perform(join(second, 101)).andExpect(jsonPath("$.position").value(2))
                .andExpect(jsonPath("$.ahead").value(1))
                .andExpect(jsonPath("$.waiting").value(2));
        // 다시 줄 서도 자리는 그대로다
        mockMvc.perform(join(first, 101)).andExpect(jsonPath("$.position").value(1));
    }

    @Test
    void 선착순이_아니면_404_inventory를_알_수_없으면_503() throws Exception {
        when(inventory.lookup(STORE, 102L)).thenReturn(FirstComeLookup.notFirstCome());
        mockMvc.perform(join(user(), 102)).andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("NOT_FIRST_COME"));
        when(inventory.lookup(STORE, 103L)).thenReturn(FirstComeLookup.unknown());
        mockMvc.perform(join(user(), 103)).andExpect(status().isServiceUnavailable())
                .andExpect(jsonPath("$.code").value("FIRST_COME_UNAVAILABLE"));
    }

    @Test
    void 고객이_아니면_403() throws Exception {
        mockMvc.perform(post("/orders/queue/104").param("storeId", "1")
                        .header("X-User-Id", user()).header("X-User-Role", "OWNER"))
                .andExpect(status().isForbidden());
    }

    @Test
    void 남은_수량만큼_앞사람부터_입장권을_5분_준다() throws Exception {
        remaining(105, 1);
        String first = user();
        String second = user();
        mockMvc.perform(join(first, 105));
        mockMvc.perform(join(second, 105));

        scheduler.admitAll();

        mockMvc.perform(me(first, 105)).andExpect(jsonPath("$.status").value("ADMITTED"))
                .andExpect(jsonPath("$.ticketExpiresAt").isNotEmpty());
        mockMvc.perform(me(second, 105)).andExpect(jsonPath("$.status").value("WAITING"))
                .andExpect(jsonPath("$.position").value(1));
        Instant expiresAt = queue.ticket(new QueueKey(STORE, 105L), first).orElseThrow();
        assertThat(Duration.between(clock.instant(), expiresAt)).isBetween(Duration.ofMinutes(4), Duration.ofMinutes(5));

        // 자리가 하나라 입장권이 살아 있는 동안 다음 사람은 들어가지 않는다
        scheduler.admitAll();
        mockMvc.perform(me(second, 105)).andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    void 입장권이_없으면_선착순_상품_주문은_409() throws Exception {
        remaining(106, 5);
        mockMvc.perform(order(user(), 106, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_COME_TICKET_REQUIRED"));
    }

    @Test
    void 입장권으로_한_번_주문하면_입장권은_사라지고_같은_상품은_다시_살_수_없다() throws Exception {
        remaining(107, 5);
        String buyer = user();
        mockMvc.perform(join(buyer, 107));
        scheduler.admitAll();

        mockMvc.perform(order(buyer, 107, 2)).andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("FIRST_COME_QUANTITY_LIMIT"));
        mockMvc.perform(order(buyer, 107, 1)).andExpect(status().isCreated());

        assertThat(queue.ticket(new QueueKey(STORE, 107L), buyer)).isEmpty();
        mockMvc.perform(order(buyer, 107, 1)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_COME_ALREADY_ORDERED"));
        mockMvc.perform(join(buyer, 107)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_COME_ALREADY_ORDERED"));
    }

    /** 입장권을 받아 주문을 만들고 주문 번호를 돌려준다 */
    private long buyWithTicket(String buyer, long productId) throws Exception {
        mockMvc.perform(join(buyer, productId));
        scheduler.admitAll();
        String body = mockMvc.perform(order(buyer, productId, 1)).andExpect(status().isCreated())
                .andReturn().getResponse().getContentAsString();
        return Long.parseLong(body.replaceAll(".*\"orderId\":(\\d+).*", "$1"));
    }

    @Test
    void 입장권_없이_산_예전_일반_주문은_1인_1개에_세지_않는다() throws Exception {
        String buyer = user();
        when(inventory.lookup(STORE, 120L)).thenReturn(FirstComeLookup.notFirstCome());
        mockMvc.perform(order(buyer, 120, 1)).andExpect(status().isCreated());

        remaining(120, 5);
        mockMvc.perform(join(buyer, 120)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    void 입장권_주문이_결제_전_취소되면_다시_줄_설_수_있다() throws Exception {
        remaining(121, 5);
        String buyer = user();
        long orderId = buyWithTicket(buyer, 121);
        mockMvc.perform(join(buyer, 121)).andExpect(status().isConflict());

        jdbc.update("UPDATE orders SET status = 'CANCELED' WHERE id = ?", orderId);
        mockMvc.perform(join(buyer, 121)).andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    void 결제_후_취소로_환불이_시작된_주문은_1인_1개에_세지_않는다() throws Exception {
        remaining(122, 5);
        String buyer = user();
        long orderId = buyWithTicket(buyer, 122);

        jdbc.update("UPDATE orders SET status = 'PAID', refund_status = 'CHECKING' WHERE id = ?", orderId);
        mockMvc.perform(join(buyer, 122)).andExpect(status().isOk());
    }

    @Test
    void 결제_완료_주문은_1인_1개에_센다() throws Exception {
        remaining(123, 5);
        String buyer = user();
        long orderId = buyWithTicket(buyer, 123);

        jdbc.update("UPDATE orders SET status = 'PAID', refund_status = 'NONE' WHERE id = ?", orderId);
        mockMvc.perform(join(buyer, 123)).andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("FIRST_COME_ALREADY_ORDERED"));
    }

    @Test
    void 주문을_만든_자리는_RESERVE_반영_전까지_다른_사람에게_주지_않는다() throws Exception {
        remaining(108, 1);
        String buyer = user();
        String next = user();
        mockMvc.perform(join(buyer, 108));
        scheduler.admitAll();
        mockMvc.perform(join(next, 108));
        mockMvc.perform(order(buyer, 108, 1)).andExpect(status().isCreated());

        // inventory가 아직 RESERVE를 반영하지 않아 남은 수량이 그대로 1이어도 다음 사람은 기다린다
        scheduler.admitAll();
        mockMvc.perform(me(next, 108)).andExpect(jsonPath("$.status").value("WAITING"));
    }

    @Test
    void 선착순이_아닌_상품은_입장권_없이_주문된다_inventory를_알_수_없어도_그렇다() throws Exception {
        when(inventory.lookup(STORE, 109L)).thenReturn(FirstComeLookup.notFirstCome());
        mockMvc.perform(order(user(), 109, 2)).andExpect(status().isCreated());
        when(inventory.lookup(STORE, 110L)).thenReturn(FirstComeLookup.unknown());
        mockMvc.perform(order(user(), 110, 2)).andExpect(status().isCreated());
    }

    @Test
    void 그만_기다리면_줄에서_빠진다() throws Exception {
        remaining(111, 0);
        String user = user();
        mockMvc.perform(join(user, 111));
        mockMvc.perform(delete("/orders/queue/111/me").param("storeId", "1")
                        .header("X-User-Id", user).header("X-User-Role", "CUSTOMER"))
                .andExpect(status().isNoContent());
        mockMvc.perform(me(user, 111)).andExpect(jsonPath("$.status").value("NOT_IN_QUEUE"));
    }

    @Test
    void 순번_조회가_30초_끊기면_줄에서_뺀다() throws Exception {
        remaining(112, 0);
        String gone = user();
        mockMvc.perform(join(gone, 112));
        QueueKey key = new QueueKey(STORE, 112L);

        queue.admit(key, 0, clock.instant().plusSeconds(20), properties.ticketTtl(), properties.idleTimeout());
        assertThat(queue.rank(key, gone)).isZero();
        queue.admit(key, 0, clock.instant().plusSeconds(31), properties.ticketTtl(), properties.idleTimeout());
        assertThat(queue.rank(key, gone)).isNull();
        // 줄과 입장권이 모두 비면 스케줄러 목록에서 내린다
        assertThat(queue.activeKeys()).doesNotContain(key.value());
    }

    @Test
    void 남은_수량이_0이고_입장권도_없으면_품절로_보여_준다() throws Exception {
        remaining(113, 0);
        mockMvc.perform(join(user(), 113)).andExpect(jsonPath("$.status").value("SOLD_OUT"))
                .andExpect(jsonPath("$.remaining").value(0));
    }

    @Test
    void 입장권은_주문을_만들지_못하면_남은_시간만큼_되돌린다() {
        QueueKey key = new QueueKey(STORE, 114L);
        String user = user();
        Instant now = clock.instant();
        Instant expiresAt = now.plus(Duration.ofMinutes(3)).truncatedTo(java.time.temporal.ChronoUnit.MILLIS);
        queue.restoreTicket(key, user, expiresAt, now);
        assertThat(queue.takeTicket(key, user, now, properties.reserveGrace())).contains(expiresAt);
        assertThat(queue.takeTicket(key, user, now, properties.reserveGrace())).isEmpty();
        queue.restoreTicket(key, user, expiresAt, now);
        assertThat(queue.ticket(key, user)).contains(expiresAt);
        assertThat(redis.getExpire("fc:ticket:" + key.value() + ":" + user)).isBetween(170L, 180L);
    }

    @Test
    void 입장_처리가_목록에서_내린_상품도_순번_조회가_다시_올린다() throws Exception {
        remaining(116, 0);
        String user = user();
        mockMvc.perform(join(user, 116));
        // admit이 "줄이 비었다"고 본 직후 이 사람이 줄을 서서 목록에서만 빠진 상황
        redis.opsForSet().remove("fc:active", "1:116");

        mockMvc.perform(me(user, 116)).andExpect(jsonPath("$.status").value("SOLD_OUT"));
        assertThat(queue.activeKeys()).contains("1:116");
    }

    @Test
    void 선착순이_꺼지면_스케줄러_목록에서_내린다() throws Exception {
        remaining(115, 0);
        mockMvc.perform(join(user(), 115));
        when(inventory.lookup(anyLong(), anyLong())).thenReturn(FirstComeLookup.notFirstCome());
        scheduler.admitAll();
        assertThat(queue.activeKeys()).doesNotContain("1:115");
    }
}
