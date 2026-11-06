package com.freshlink.order.domain.order;

import java.util.Collection;
import java.util.List;
import org.springframework.data.jpa.repository.JpaRepository;

public interface OrderLotRepository extends JpaRepository<OrderLot, Long> {

    /** 주문 목록의 줄(마감세일·기본)을 쿼리 한 번으로 읽는다 */
    List<OrderLot> findByOrderIdIn(Collection<Long> orderIds);

    List<OrderLot> findByOrderId(Long orderId);
}
