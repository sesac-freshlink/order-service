package com.freshlink.order.domain.payment;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.payment.toss.TossConfirmResult;
import com.freshlink.order.domain.payment.toss.TossOutcome;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.EnumSet;
import java.util.Set;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * OR-03 결제 승인. 트랜잭션을 둘로 나눈다.
 * 1) 주문을 잠그고 검사한 뒤 PAYMENT_APPROVING + 결제 기록 저장 (커밋)
 * 2) 트랜잭션 밖에서 토스 승인 호출 (타임아웃)
 * 3) 다시 잠그고 결과 반영: PAID + CONFIRM / PAYMENT_FAILED + RELEASE / 그대로 (결과 불명)
 * 토스 호출 중에 DB 잠금을 잡고 있지 않기 위해서다
 */
@Service
public class PaymentService {

    private static final Logger log = LoggerFactory.getLogger(PaymentService.class);

    /** 승인 처리가 이미 시작된 상태. 같은 paymentKey면 지금 상태를 돌려준다 */
    private static final Set<OrderStatus> APPROVAL_STARTED =
            EnumSet.of(OrderStatus.PAYMENT_APPROVING, OrderStatus.PAID, OrderStatus.PAYMENT_FAILED);

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient toss;
    private final PaymentResultWriter resultWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public PaymentService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                          TossPaymentsClient toss, PaymentResultWriter resultWriter,
                          TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.toss = toss;
        this.resultWriter = resultWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    public PaymentConfirmResponse confirm(String customerId, Long orderId, PaymentConfirmRequest req) {
        Start start = transactionTemplate.execute(s -> begin(customerId, orderId, req));
        if (start.tossKey() == null) {
            return PaymentConfirmResponse.from(start.order());
        }
        TossConfirmResult result = callToss(req, start);
        Order order = transactionTemplate.execute(s -> apply(orderId, result));
        return PaymentConfirmResponse.from(order);
    }

    /** 승인 전 검사 (명세 OR-03 순서). 통과하면 PAYMENT_APPROVING으로 바꾸고 결제 기록을 남긴다 */
    private Start begin(String customerId, Long orderId, PaymentConfirmRequest req) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (!order.getCustomerId().equals(customerId)) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }
        if (!req.orderNo().equals(order.getOrderNo())) {
            throw new BusinessException(OrderErrorCode.ORDER_NO_MISMATCH);
        }
        // 금액은 RESERVED 뒤에 정해진다. 아직 없으면 아래 상태 검사에서 409로 막는다
        if (order.getAmount() != null && !order.getAmount().equals(req.amount())) {
            throw new BusinessException(OrderErrorCode.PAYMENT_AMOUNT_MISMATCH);
        }
        if (APPROVAL_STARTED.contains(order.getStatus())) {
            Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow(() ->
                    new IllegalStateException("승인이 시작된 주문에 결제 기록이 없다: orderId=" + orderId));
            if (!payment.getPaymentKey().equals(req.paymentKey())) {
                throw new BusinessException(OrderErrorCode.PAYMENT_INFO_MISMATCH);
            }
            log.info("결제 승인 재요청 orderId={} status={}", orderId, order.getStatus());
            return new Start(order, null);
        }
        if (order.getStatus() != OrderStatus.PAYMENT_WAITING) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_PAYABLE);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!now.isBefore(order.getPaymentExpiresAt())) {
            throw new BusinessException(OrderErrorCode.ORDER_EXPIRED);
        }
        if (!toss.isConfigured()) {
            // 부르기 전에 알 수 있는 설정 오류다. 주문을 승인 중에 묶어 두지 않고 그대로 둔다
            throw new IllegalStateException("TOSS_SECRET_KEY가 설정되지 않아 결제 승인을 시작하지 않는다: orderId=" + orderId);
        }
        order.startApproval(now);
        String tossKey = UUID.randomUUID().toString();
        paymentRepository.save(Payment.start(orderId, req.paymentKey(), tossKey, req.amount()));
        log.info("결제 승인 시작 orderId={} amount={}", orderId, req.amount());
        return new Start(order, tossKey);
    }

    private TossConfirmResult callToss(PaymentConfirmRequest req, Start start) {
        try {
            return toss.confirm(req.paymentKey(), start.order().getOrderNo(), req.amount(), start.tossKey());
        } catch (RuntimeException e) {
            // 클라이언트는 예외를 던지지 않게 만들었지만, 혹시 나도 결제 여부를 모르므로 결과 불명으로 둔다
            log.error("토스 승인 호출 중 예외 orderId={}", start.order().getId(), e);
            return TossConfirmResult.error(TossOutcome.UNKNOWN, null, e.getClass().getSimpleName());
        }
    }

    /** 결과 반영. 그사이 다른 경로(OP-01 등)가 먼저 바꿨으면 손대지 않는다 */
    private Order apply(Long orderId, TossConfirmResult result) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
        if (order.getStatus() != OrderStatus.PAYMENT_APPROVING) {
            log.warn("결과를 반영하려는데 이미 승인 중이 아니다 orderId={} status={} outcome={}",
                    orderId, order.getStatus(), result.outcome());
            return order;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (result.outcome()) {
            case APPROVED -> {
                OffsetDateTime paidAt = result.approvedAt() != null ? result.approvedAt() : now;
                resultWriter.paid(order, payment, result.tossStatus(), result.method(), paidAt);
                log.info("결제 완료 orderId={} amount={}", orderId, order.getAmount());
            }
            case DEFINITE_FAILURE -> {
                resultWriter.failed(order, payment, result.errorCode(), result.errorMessage(), now);
                log.info("결제 실패 orderId={} code={} message={}", orderId, result.errorCode(), result.errorMessage());
            }
            case CONFIG_ERROR -> {
                payment.recordError(result.errorCode(), result.errorMessage());
                log.error("토스 연동·설정 오류. 승인 중으로 둔다 (개발자 확인) orderId={} code={} message={}",
                        orderId, result.errorCode(), result.errorMessage());
            }
            default -> {
                payment.recordError(result.errorCode(), result.errorMessage());
                log.warn("결제 결과 불명. 승인 중으로 둔다 (OP-01로 확인) orderId={} outcome={} code={} message={}",
                        orderId, result.outcome(), result.errorCode(), result.errorMessage());
            }
        }
        return order;
    }

    /** tossKey가 null이면 이미 승인이 시작된 주문의 재요청이다 */
    private record Start(Order order, String tossKey) {
    }
}
