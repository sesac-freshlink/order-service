package com.freshlink.order.domain.payment;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import java.time.Clock;
import java.time.Duration;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A11 결제 결과 자동 조회 (추가 명세 A11). 결과 불명으로 PAYMENT_APPROVING에 머문 주문을 서버가 토스에 다시 물어본다.
 * 결과 불명이 된 직후 첫 조회, 이후 30초마다 추가 10번. 그래도 모르면 예약을 지우고 수동 확인(OP-01) 대상으로 경고를 남긴다.
 * 조회·반영은 OP-01(PaymentSyncService)을 그대로 쓴다. 조회에서 못 찾았다는 이유만으로 실패 처리하지 않는다.
 * 상태는 PAYMENT_APPROVING을 그대로 쓴다(PAYMENT_CHECKING을 따로 두지 않음, 10/29 결정). 만료·재고 해제는 이 상태를 건드리지 않는다
 */
@Service
public class PaymentAutoSyncService {

    private static final Logger log = LoggerFactory.getLogger(PaymentAutoSyncService.class);
    static final int BATCH_SIZE = 50;
    /** 첫 조회 1번 + 추가 10번 */
    static final int MAX_ATTEMPTS = 11;
    static final Duration INTERVAL = Duration.ofSeconds(30);

    private final PaymentRepository paymentRepository;
    private final OrderRepository orderRepository;
    private final PaymentSyncService paymentSyncService;
    private final TossPaymentsClient toss;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PaymentAutoSyncService(PaymentRepository paymentRepository, OrderRepository orderRepository,
                                  PaymentSyncService paymentSyncService, TossPaymentsClient toss,
                                  TransactionTemplate transactionTemplate, Clock clock) {
        this.paymentRepository = paymentRepository;
        this.orderRepository = orderRepository;
        this.paymentSyncService = paymentSyncService;
        this.toss = toss;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 차례가 된 결제를 조회한다. 조회한 건수를 돌려준다 */
    public int syncDue() {
        if (!toss.isConfigured()) {
            // 키가 없으면 횟수를 쓰지 않고 다음 차례로 미룬다 (연동 설정 문제는 개발자 확인)
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> orderIds = paymentRepository.findSyncDueOrderIds(PaymentStatus.APPROVING, now,
                PageRequest.of(0, BATCH_SIZE));
        for (Long orderId : orderIds) {
            OrderStatus after;
            try {
                after = paymentSyncService.sync(orderId).after();
            } catch (BusinessException e) {
                // 이미 승인 중이 아니다 (ORDER_NOT_SYNCABLE 등). 더 볼 필요가 없다
                after = null;
            } catch (RuntimeException e) {
                // 조회 중 오류도 결과 불명과 같이 다룬다. 횟수를 쓰고 다음에 다시 본다
                log.error("결제 자동 조회 실패 orderId={}", orderId, e);
                after = OrderStatus.PAYMENT_APPROVING;
            }
            OrderStatus result = after;
            transactionTemplate.executeWithoutResult(s -> record(orderId, result, now));
        }
        return orderIds.size();
    }

    /** 조회 결과에 따라 다음 예약을 정한다. 주문을 잠가 OP-01·승인 요청과 겹쳐도 차례대로 바뀐다 */
    private void record(Long orderId, OrderStatus after, OffsetDateTime now) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        Payment payment = paymentRepository.findByOrderId(orderId).orElse(null);
        if (order == null || payment == null) {
            return;
        }
        if (after != OrderStatus.PAYMENT_APPROVING || order.getStatus() != OrderStatus.PAYMENT_APPROVING) {
            payment.stopSync();
            return;
        }
        if (payment.afterSyncAttempt(now, INTERVAL, MAX_ATTEMPTS)) {
            log.warn("결제 결과 자동 조회 {}번에도 결과 불명. 수동 확인(OP-01) 대상 orderId={} paymentKey={}",
                    payment.getSyncAttempts(), orderId, payment.getPaymentKey());
        }
    }
}
