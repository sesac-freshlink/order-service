package com.freshlink.order.domain.payment;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.order.PickupRequests;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * 시뮬레이터 주문 결제 완료 (3차 할 일 1-3). 토스를 부르지 않고 결제 기록(SIM- 키)을 승인 완료로 남긴 뒤
 * OR-03과 같은 {@link PaymentResultWriter#paid}로 PAID + CONFIRM + 점주 새 주문 알림 + 매출 SSE를 낸다.
 * app.simulated-payment.enabled=true인 환경(시뮬레이터가 도는 곳)에만 빈이 생긴다
 */
@Service
@ConditionalOnProperty(prefix = "app.simulated-payment", name = "enabled", havingValue = "true")
public class SimulatedPaymentService {

    private static final Logger log = LoggerFactory.getLogger(SimulatedPaymentService.class);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final PaymentResultWriter resultWriter;
    private final PickupRequests pickupRequests;
    private final Clock clock;

    public SimulatedPaymentService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                                   PaymentResultWriter resultWriter, PickupRequests pickupRequests, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.resultWriter = resultWriter;
        this.pickupRequests = pickupRequests;
        this.clock = clock;
    }

    /** PAYMENT_WAITING·결제 기한·픽업 기한 이내 주문만. 이미 SIM- 결제로 PAID면 지금 상태를 돌려준다(다시 불러도 된다) */
    @Transactional
    public PaymentConfirmResponse pay(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() == OrderStatus.PAID || order.getStatus() == OrderStatus.PICKUP_REQUESTED) {
            boolean simulated = paymentRepository.findByOrderId(orderId)
                    .map(p -> SimulatedPayments.isSimulated(p.getPaymentKey())).orElse(false);
            if (simulated) {
                return PaymentConfirmResponse.from(order);
            }
        }
        if (order.getStatus() != OrderStatus.PAYMENT_WAITING) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!now.isBefore(order.getPaymentExpiresAt())) {
            throw new BusinessException(OrderErrorCode.ORDER_EXPIRED);
        }
        if (order.isPickupDeadlinePassed(now)) {
            throw new BusinessException(OrderErrorCode.PICKUP_DEADLINE_PASSED);
        }
        order.startApproval(now);
        Payment payment = paymentRepository.saveAndFlush(Payment.start(orderId,
                SimulatedPayments.KEY_PREFIX + UUID.randomUUID(), UUID.randomUUID().toString(), order.getAmount()));
        resultWriter.paid(order, payment, SimulatedPayments.STATUS_DONE, SimulatedPayments.METHOD, null, now);
        // 시뮬레이터에는 [픽업 신청]을 누를 사람이 없어 결제와 함께 신청한다. 시뮬레이터는 그대로 /ready를 부른다(11/10 결정)
        if (order.getStatus() == OrderStatus.PAID) {
            pickupRequests.requestIfPossible(order, now);
        }
        log.info("시뮬레이터 결제 완료 orderId={} storeId={} amount={}", orderId, order.getStoreId(), order.getAmount());
        return PaymentConfirmResponse.from(order);
    }
}
