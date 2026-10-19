package com.freshlink.order.domain.order;

import com.freshlink.order.TestcontainersConfiguration;
import com.freshlink.order.common.config.JpaAuditingConfig;
import java.util.Map;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest;
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase;
import org.springframework.boot.jpa.test.autoconfigure.TestEntityManager;
import org.springframework.context.annotation.Import;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** 실제 MySQL(Testcontainers)에 Flyway V1·V2를 적용해 orders 매핑을 확인한다 */
@DataJpaTest
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
@Import({TestcontainersConfiguration.class, JpaAuditingConfig.class})
class OrderRepositoryTest {

    @Autowired
    OrderRepository orderRepository;

    @Autowired
    TestEntityManager em;

    @Autowired
    JdbcTemplate jdbcTemplate;

    private Order newOrder(String customerId, String requestId) {
        return Order.create(customerId, requestId, 1L, "프레시마트 강남점", 10L, 2);
    }

    private String uuid() {
        return UUID.randomUUID().toString();
    }

    @Test
    void 저장_후_조회하면_필드가_같고_상태는_RESERVING() {
        String customerId = uuid();
        String requestId = uuid();
        Long id = orderRepository.saveAndFlush(newOrder(customerId, requestId)).getId();
        em.clear();

        Order found = orderRepository.findById(id).orElseThrow();

        assertThat(found.getId()).isNotNull();
        assertThat(found.getCustomerId()).isEqualTo(customerId);
        assertThat(found.getRequestId()).isEqualTo(requestId);
        assertThat(found.getStoreId()).isEqualTo(1L);
        assertThat(found.getStoreName()).isEqualTo("프레시마트 강남점");
        assertThat(found.getProductId()).isEqualTo(10L);
        assertThat(found.getQuantity()).isEqualTo(2);
        assertThat(found.getStatus()).isEqualTo(OrderStatus.RESERVING);
        assertThat(found.getFailureReason()).isNull();
    }

    @Test
    void 저장하면_생성_수정_시각이_자동으로_채워진다() {
        Long id = orderRepository.saveAndFlush(newOrder(uuid(), uuid())).getId();
        em.clear();

        Order found = orderRepository.findById(id).orElseThrow();

        assertThat(found.getCreatedAt()).isNotNull();
        assertThat(found.getUpdatedAt()).isNotNull();
    }

    @Test
    void 고객ID와_요청ID로_찾는다() {
        String customerId = uuid();
        String requestId = uuid();
        Long id = orderRepository.saveAndFlush(newOrder(customerId, requestId)).getId();
        em.clear();

        assertThat(orderRepository.findByCustomerIdAndRequestId(customerId, requestId))
                .get().extracting(Order::getId).isEqualTo(id);
        assertThat(orderRepository.findByCustomerIdAndRequestId(customerId, uuid())).isEmpty();
        assertThat(orderRepository.findByCustomerIdAndRequestId(uuid(), requestId)).isEmpty();
    }

    @Test
    void 같은_고객ID_요청ID_두번_저장하면_UK_위반() {
        String customerId = uuid();
        String requestId = uuid();
        orderRepository.saveAndFlush(newOrder(customerId, requestId));

        assertThatThrownBy(() -> orderRepository.saveAndFlush(newOrder(customerId, requestId)))
                .isInstanceOf(DataIntegrityViolationException.class);
    }

    @Test
    void 상태는_enum_이름_문자열로_저장된다() {
        Long id = orderRepository.saveAndFlush(newOrder(uuid(), uuid())).getId();

        Map<String, Object> row = jdbcTemplate.queryForMap(
                "SELECT status, failure_reason, order_no FROM orders WHERE id = ?", id);

        assertThat(row.get("status")).isEqualTo("RESERVING");
        assertThat(row.get("failure_reason")).isNull();
        assertThat(row.get("order_no")).isNull();
    }

    @Test
    void DB의_실패사유_문자열을_enum으로_읽는다() {
        String customerId = uuid();
        String requestId = uuid();
        jdbcTemplate.update(
                "INSERT INTO orders (customer_id, request_id, store_id, store_name, product_id, quantity,"
                        + " status, failure_reason, available_quantity, created_at, updated_at)"
                        + " VALUES (?, ?, 1, '프레시마트 강남점', 10, 3, 'OUT_OF_STOCK', 'OUT_OF_STOCK', 2,"
                        + " NOW(6), NOW(6))",
                customerId, requestId);

        Order found = orderRepository.findByCustomerIdAndRequestId(customerId, requestId).orElseThrow();

        assertThat(found.getStatus()).isEqualTo(OrderStatus.OUT_OF_STOCK);
        assertThat(found.getFailureReason()).isEqualTo(OrderFailureReason.OUT_OF_STOCK);
        assertThat(found.getAvailableQuantity()).isEqualTo(2);
    }
}
