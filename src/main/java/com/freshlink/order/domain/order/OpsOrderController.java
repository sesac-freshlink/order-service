package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.PaymentSyncResponse;
import com.freshlink.order.domain.payment.PaymentSyncService;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

/**
 * 운영 API (명세 4장). gateway에 라우트가 없어 밖에서 부를 수 없다.
 * 개발자가 kubectl port-forward로 order 파드에 직접 붙어 부른다. 클러스터 안에서는 누구나 부를 수 있어 MVP 테스트 환경 한정이다
 */
@RestController
@RequestMapping("/ops/orders")
public class OpsOrderController {

    private final PaymentSyncService paymentSyncService;
    private final OrderCloseService orderCloseService;

    public OpsOrderController(PaymentSyncService paymentSyncService, OrderCloseService orderCloseService) {
        this.paymentSyncService = paymentSyncService;
        this.orderCloseService = orderCloseService;
    }

    /** OP-01 결제 결과 수동 확인 */
    @PostMapping("/{orderId}/payment-sync")
    public PaymentSyncResponse syncPayment(@PathVariable Long orderId) {
        return paymentSyncService.sync(orderId);
    }

    /** OP-02 멈춘 주문 수동 종료 */
    @PostMapping("/{orderId}/close")
    public OrderCloseResponse close(@PathVariable Long orderId) {
        return orderCloseService.close(orderId);
    }
}
