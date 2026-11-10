package com.freshlink.order.domain.withdrawal;

import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import java.util.EnumSet;
import java.util.Set;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 회원탈퇴 연동 (추가 명세 A14, OR-12): member-service가 탈퇴 전에 고객의 진행 중 주문이 있는지 묻는다.
 * 클러스터 안 서비스끼리만 부른다. gateway는 /api/** 만 넘기므로 /internal/** 은 밖에서 닿지 않는다(/ops/** 와 같다)
 */
@RestController
@RequestMapping("/internal/customers")
public class CustomerActiveOrdersController {

    /**
     * 추가 명세 0.4 진행 중: 재고 확보 중·결제 대기·승인 중·결제 완료·픽업 신청됨(11/10)·픽업 준비 완료.
     * 환불 처리 중·확인 중·실패인 결제 완료 주문도 상태가 PAID라 여기 든다
     */
    static final Set<OrderStatus> ACTIVE = EnumSet.of(OrderStatus.RESERVING, OrderStatus.PAYMENT_WAITING,
            OrderStatus.PAYMENT_APPROVING, OrderStatus.PAID, OrderStatus.PICKUP_REQUESTED, OrderStatus.READY_FOR_PICKUP);

    private final OrderRepository orderRepository;

    public CustomerActiveOrdersController(OrderRepository orderRepository) {
        this.orderRepository = orderRepository;
    }

    @GetMapping("/{customerId}/active-orders")
    @Transactional(readOnly = true)
    public ActiveOrdersResponse activeOrders(@PathVariable String customerId) {
        return new ActiveOrdersResponse(orderRepository.countByCustomerIdAndStatusIn(customerId, ACTIVE));
    }

    /** 진행 중 주문 수. 0이면 탈퇴할 수 있다 */
    public record ActiveOrdersResponse(long activeOrders) {
    }
}
