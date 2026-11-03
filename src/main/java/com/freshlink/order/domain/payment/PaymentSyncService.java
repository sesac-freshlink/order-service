package com.freshlink.order.domain.payment;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.payment.toss.TossLookupResult;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OP-01 결제 결과 수동 확인. PAYMENT_APPROVING에 머문 주문을 토스 결제 조회로 확인해 반영한다 (명세 OP-01 표).
 * 토스 조회는 트랜잭션 밖에서 하고, 반영은 주문을 다시 잠근 뒤 아직 PAYMENT_APPROVING일 때만 한다
 */
@Service
public class PaymentSyncService {

    private static final Logger log = LoggerFactory.getLogger(PaymentSyncService.class);
    private static final String DONE = "DONE";
    private static final Set<String> FINAL_FAILURE = Set.of("ABORTED", "EXPIRED");
    private static final Set<String> SHOULD_NOT_HAPPEN = Set.of("CANCELED", "PARTIAL_CANCELED");

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient toss;
    private final PaymentResultWriter resultWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PaymentSyncService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                              TossPaymentsClient toss, PaymentResultWriter resultWriter,
                              TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.toss = toss;
        this.resultWriter = resultWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public PaymentSyncResponse sync(Long orderId) {
        String paymentKey = transactionTemplate.execute(s -> {
            Order order = orderRepository.findById(orderId)
                    .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
            if (order.getStatus() != OrderStatus.PAYMENT_APPROVING) {
                throw new BusinessException(OrderErrorCode.ORDER_NOT_SYNCABLE);
            }
            return paymentRepository.findByOrderId(orderId).orElseThrow(() ->
                    new IllegalStateException("승인 중인 주문에 결제 기록이 없다: orderId=" + orderId)).getPaymentKey();
        });
        if (!toss.isConfigured()) {
            throw new IllegalStateException("TOSS_SECRET_KEY가 설정되지 않아 결제 조회를 하지 않는다: orderId=" + orderId);
        }
        TossLookupResult result = toss.lookup(paymentKey);
        OrderStatus after = transactionTemplate.execute(s -> apply(orderId, result));
        return new PaymentSyncResponse(orderId, OrderStatus.PAYMENT_APPROVING, after, result.status());
    }

    private OrderStatus apply(Long orderId, TossLookupResult result) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PAYMENT_APPROVING) {
            log.warn("결제 확인 결과를 반영하려는데 이미 승인 중이 아니다 orderId={} status={}", orderId, order.getStatus());
            return order.getStatus();
        }
        if (!result.found()) {
            // 조회 실패·결제 없음만으로 실패 처리하지 않는다
            log.warn("토스 결제 조회 실패. 승인 중으로 둔다 orderId={} code={} message={}",
                    orderId, result.errorCode(), result.errorMessage());
            return order.getStatus();
        }
        if (!matchesOrder(order, result)) {
            // 저장된 paymentKey가 다른 결제를 가리키면 이 주문을 확정하지 않는다 (성공·실패 모두)
            log.error("결제 확인: 토스 결제가 이 주문과 맞지 않아 그대로 둔다 (개발자 확인) orderId={} orderNo={} amount={} "
                            + "tossOrderId={} tossAmount={}",
                    orderId, order.getOrderNo(), order.getAmount(), result.orderId(), result.totalAmount());
            return order.getStatus();
        }
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        OffsetDateTime now = OffsetDateTime.now(clock);
        String status = result.status();
        if (DONE.equals(status)) {
            resultWriter.paid(order, payment, status, result.method(), result.receiptUrl(),
                    result.approvedAt() != null ? result.approvedAt() : now);
            log.info("결제 확인: 승인 성공으로 반영 orderId={}", orderId);
        } else if (FINAL_FAILURE.contains(status)) {
            resultWriter.failed(order, payment, null, "토스 결제 상태 " + status, now);
            log.info("결제 확인: 최종 실패로 반영 orderId={} tossStatus={}", orderId, status);
        } else if (SHOULD_NOT_HAPPEN.contains(status)) {
            log.error("결제 확인: MVP에서 생기면 안 되는 토스 상태라 그대로 둔다 (개발자 확인) orderId={} tossStatus={}",
                    orderId, status);
        } else {
            log.info("결제 확인: 아직 결과 불명이라 그대로 둔다 orderId={} tossStatus={}", orderId, status);
        }
        return order.getStatus();
    }

    /** 토스 결제의 주문번호·금액이 이 주문과 같은지 본다 */
    private static boolean matchesOrder(Order order, TossLookupResult result) {
        return order.getOrderNo() != null && order.getOrderNo().equals(result.orderId())
                && order.getAmount() != null && order.getAmount().equals(result.totalAmount());
    }
}
