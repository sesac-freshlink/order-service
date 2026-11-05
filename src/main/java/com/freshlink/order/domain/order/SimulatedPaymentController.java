package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.PaymentConfirmResponse;
import com.freshlink.order.domain.payment.SimulatedPaymentService;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 API: 시뮬레이터 주문 결제 완료 (3차 할 일 1-3). /ops/**라 gateway 라우트가 없어 밖에서 부를 수 없다(OP-01·OP-02와 같다).
 * logistics-simulator가 클러스터 안에서 부른다. app.simulated-payment.enabled=true일 때만 열린다(기본 false → 404)
 */
@RestController
@RequestMapping("/ops/orders")
@ConditionalOnProperty(prefix = "app.simulated-payment", name = "enabled", havingValue = "true")
public class SimulatedPaymentController {

    private final SimulatedPaymentService simulatedPaymentService;

    public SimulatedPaymentController(SimulatedPaymentService simulatedPaymentService) {
        this.simulatedPaymentService = simulatedPaymentService;
    }

    @PostMapping("/{orderId}/simulated-payment")
    public PaymentConfirmResponse pay(@PathVariable Long orderId) {
        return simulatedPaymentService.pay(orderId);
    }
}
