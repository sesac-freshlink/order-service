package com.freshlink.order.domain.withdrawal;

import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
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

/** 회원탈퇴 연동 OR-12 (추가 명세 A14): 고객의 진행 중 주문 수를 실제 MySQL에서 확인한다 */
@SpringBootTest(properties = {"freshlink.messaging.outbox.publish-interval=1h",
        "app.queue.commands=test-inventory-commands.fifo", "app.queue.results=test-inventory-results",
        "app.queue.notifications=test-notification-events",
        "spring.cloud.aws.sqs.listener.auto-startup=false", "app.expiry.enabled=false"})
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
class CustomerActiveOrdersIntegrationTest {

    @Autowired
    MockMvc mockMvc;
    @Autowired
    OrderRepository orderRepository;
    @Autowired
    JdbcTemplate jdbc;
    @MockitoBean
    SqsTemplate sqsTemplate;

    private void order(String customer, String status, String refund) {
        Order o = orderRepository.saveAndFlush(Order.create(customer, UUID.randomUUID().toString(), 1L, "점포", 15L, 1));
        jdbc.update("UPDATE orders SET status = ?, refund_status = ? WHERE id = ?", status, refund, o.getId());
    }

    @Test
    void 진행_중_주문만_센다() throws Exception {
        String customer = UUID.randomUUID().toString();
        for (String s : new String[] {"RESERVING", "PAYMENT_WAITING", "PAYMENT_APPROVING", "PAID", "READY_FOR_PICKUP"}) {
            order(customer, s, "NONE");
        }
        for (String s : new String[] {"OUT_OF_STOCK", "PAYMENT_FAILED", "EXPIRED", "CANCELED", "PICKED_UP",
                "PICKUP_EXPIRED", "REFUNDED"}) {
            order(customer, s, "NONE");
        }
        mockMvc.perform(get("/internal/customers/" + customer + "/active-orders"))
                .andExpect(status().isOk()).andExpect(jsonPath("$.activeOrders").value(5));
    }

    @Test
    void 환불_처리_중_결제_완료도_진행_중이고_종료뿐이면_0() throws Exception {
        String refunding = UUID.randomUUID().toString();
        order(refunding, "PAID", "FAILED");
        mockMvc.perform(get("/internal/customers/" + refunding + "/active-orders"))
                .andExpect(jsonPath("$.activeOrders").value(1));

        String done = UUID.randomUUID().toString();
        order(done, "PICKED_UP", "NONE");
        order(done, "REFUNDED", "COMPLETED");
        mockMvc.perform(get("/internal/customers/" + done + "/active-orders"))
                .andExpect(jsonPath("$.activeOrders").value(0));
        mockMvc.perform(get("/internal/customers/" + UUID.randomUUID() + "/active-orders"))
                .andExpect(jsonPath("$.activeOrders").value(0));
    }
}
