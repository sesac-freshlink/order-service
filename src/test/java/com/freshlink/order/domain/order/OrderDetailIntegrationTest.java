package com.freshlink.order.domain.order;

import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.order.TestcontainersConfiguration;
import io.awspring.cloud.sqs.operations.SqsTemplate;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.test.context.bean.override.mockito.MockitoBean;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** OR-02 주문 조회를 실제 MySQL에서 확인한다. 저장한 시각을 다시 읽어도 +09:00으로 나가는지 본다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results", "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class OrderDetailIntegrationTest {

    private static final String KST_PATTERN = "^\\d{4}-\\d{2}-\\d{2}T\\d{2}:\\d{2}:\\d{2}(\\.\\d+)?\\+09:00$";

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    ReservationResultHandler reservationResultHandler;

    @MockitoBean
    SqsTemplate sqsTemplate;

    private Order newOrder(String customer) {
        return orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(),
                1L, "프레시 강남점", 15L, 2));
    }

    private MockHttpServletRequestBuilder getAs(String customer, long orderId) {
        return get("/orders/" + orderId).header("X-User-Id", customer).header("X-User-Role", "CUSTOMER");
    }

    @Test
    void 방금_만든_주문은_RESERVING이고_금액_관련_값은_null() throws Exception {
        String customer = UUID.randomUUID().toString();
        Order order = newOrder(customer);

        mockMvc.perform(getAs(customer, order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.orderId").value(order.getId()))
                .andExpect(jsonPath("$.status").value("RESERVING"))
                .andExpect(jsonPath("$.storeName").value("프레시 강남점"))
                .andExpect(jsonPath("$.quantity").value(2))
                .andExpect(jsonPath("$.productName").isEmpty())
                .andExpect(jsonPath("$.amount").isEmpty())
                .andExpect(jsonPath("$.paymentExpiresAt").isEmpty())
                .andExpect(jsonPath("$.createdAt").value(org.hamcrest.Matchers.matchesPattern(KST_PATTERN)));
    }

    @Test
    void 재고가_잡히면_결제_대기와_금액_결제_마감이_보인다() throws Exception {
        String customer = UUID.randomUUID().toString();
        Order order = newOrder(customer);
        reservationResultHandler.handle(ReservedMessage.of(order.getId(), 1L, 15L, "매운 컵라면 115g", 2, 1700L,
                List.of(new ReservedLot(101L, 2, OffsetDateTime.parse("2026-10-25T23:59:59+09:00")))));

        mockMvc.perform(getAs(customer, order.getId()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status").value("PAYMENT_WAITING"))
                .andExpect(jsonPath("$.failureReason").isEmpty())
                .andExpect(jsonPath("$.productName").value("매운 컵라면 115g"))
                .andExpect(jsonPath("$.unitPrice").value(1700))
                .andExpect(jsonPath("$.amount").value(3400))
                .andExpect(jsonPath("$.paymentExpiresAt").value(org.hamcrest.Matchers.matchesPattern(KST_PATTERN)))
                .andExpect(jsonPath("$.paidAt").isEmpty());
    }

    @Test
    void 남의_주문은_403_ORDER_ACCESS_DENIED() throws Exception {
        Order order = newOrder(UUID.randomUUID().toString());

        mockMvc.perform(getAs(UUID.randomUUID().toString(), order.getId()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code").value("ORDER_ACCESS_DENIED"));
    }

    @Test
    void 없는_주문은_404_ORDER_NOT_FOUND() throws Exception {
        mockMvc.perform(getAs(UUID.randomUUID().toString(), Long.MAX_VALUE))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code").value("ORDER_NOT_FOUND"));
    }
}
