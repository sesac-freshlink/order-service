package com.freshlink.order.domain.refund;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.order.OrderStatus;
import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentRepository;
import com.freshlink.order.domain.payment.toss.TossCancelOutcome;
import com.freshlink.order.domain.payment.toss.TossCancelResult;
import com.freshlink.order.domain.payment.toss.TossPaymentsClient;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.UUID;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A8 결제 후 취소 요청(고객·점주)과 A9 환불 처리. 결제 승인(PaymentService)과 같이 트랜잭션을 둘로 나눈다.
 * 1) 주문을 잠그고 검사한 뒤 환불 REQUESTED + 시도 저장 (커밋). 픽업 확인도 같은 행을 잠그므로 먼저 시작한 쪽만 된다
 * 2) 트랜잭션 밖에서 토스 전액 취소 호출 (타임아웃, 시도마다 새 멱등키)
 * 3) 다시 잠그고 결과 반영: REFUNDED + RETURN / 환불 FAILED / 환불 CHECKING (결과 불명, 조회로 확인)
 */
@Service
public class RefundService {

    private static final Logger log = LoggerFactory.getLogger(RefundService.class);
    /** 기타 상세 최대 길이. 토스 cancelReason 최대 200자에 맞춘다 */
    static final int REASON_DETAIL_MAX = 200;

    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final RefundRepository refundRepository;
    private final TossPaymentsClient toss;
    private final RefundResultWriter resultWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RefundService(OrderRepository orderRepository, PaymentRepository paymentRepository,
                         RefundRepository refundRepository, TossPaymentsClient toss, RefundResultWriter resultWriter,
                         TransactionTemplate transactionTemplate, Clock clock) {
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.refundRepository = refundRepository;
        this.toss = toss;
        this.resultWriter = resultWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 고객 취소: 본인 주문만 */
    public CancelResponse cancelByCustomer(String customerId, Long orderId, CancelRequest req) {
        return cancel(new Requester(CancelRequester.CUSTOMER, customerId, null), orderId, req);
    }

    /** 점주 취소: 자기 점포 주문만 (X-Store-Id). 고객 계정이 지워졌어도 처리한다 */
    public CancelResponse cancelByOwner(Long storeId, String ownerId, Long orderId, CancelRequest req) {
        return cancel(new Requester(CancelRequester.OWNER, ownerId, storeId), orderId, req);
    }

    private CancelResponse cancel(Requester requester, Long orderId, CancelRequest req) {
        Start start = transactionTemplate.execute(s -> begin(requester, orderId, req));
        TossCancelResult result = callToss(start);
        return transactionTemplate.execute(s -> apply(orderId, start.refundId(), result));
    }

    /** 취소 전 검사: 없으면 404, 남의 주문·점포면 403, 사유가 틀리면 400, 처리 중이면 409, 기한·상태가 안 되면 409 */
    private Start begin(Requester requester, Long orderId, CancelRequest req) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (requester.type() == CancelRequester.CUSTOMER && !order.getCustomerId().equals(requester.userId())) {
            throw new BusinessException(OrderErrorCode.ORDER_ACCESS_DENIED);
        }
        if (requester.type() == CancelRequester.OWNER && !order.getStoreId().equals(requester.storeId())) {
            throw new BusinessException(OrderErrorCode.STORE_ORDER_ACCESS_DENIED);
        }
        CancelReason reason = parseReason(req, requester.type());
        String detail = reason.needsDetail() ? req.reasonDetail().trim() : null;
        if (order.getRefundStatus().inProgress()) {
            throw new BusinessException(OrderErrorCode.REFUND_IN_PROGRESS);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        if (!order.canStartRefund(now)) {
            // A4 요청 시점 기한 검사: 스케줄러가 아직 PICKUP_EXPIRED로 바꾸지 않았어도 신규 취소는 막는다
            if (order.getStatus() == OrderStatus.PAID && order.isPickupDeadlinePassed(now)) {
                throw new BusinessException(OrderErrorCode.PICKUP_DEADLINE_PASSED);
            }
            throw new BusinessException(OrderErrorCode.ORDER_NOT_CANCELABLE);
        }
        if (!toss.isConfigured()) {
            // 부르기 전에 알 수 있는 설정 오류다. 환불을 처리 중으로 묶어 두지 않고 그대로 둔다
            throw new IllegalStateException("TOSS_SECRET_KEY가 설정되지 않아 환불을 시작하지 않는다: orderId=" + orderId);
        }
        Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow(() ->
                new IllegalStateException("결제 완료 주문에 결제 기록이 없다: orderId=" + orderId));
        order.startRefund(now);
        int attemptNo = refundRepository.countByOrderId(orderId) + 1;
        Refund refund = refundRepository.saveAndFlush(Refund.start(orderId, attemptNo, requester.type(),
                requester.userId(), reason, detail, order.getAmount(), UUID.randomUUID().toString(), now));
        log.info("환불 시작 orderId={} attempt={} requestedBy={} reason={}", orderId, attemptNo, requester.type(), reason);
        return new Start(refund.getId(), payment.getPaymentKey(), refund.tossCancelReason(),
                refund.getTossIdempotencyKey());
    }

    /** 사유는 요청자가 고를 수 있는 것이어야 하고, 기타는 상세가 필수다 (추가 명세 A8 공통) */
    private static CancelReason parseReason(CancelRequest req, CancelRequester requester) {
        CancelReason reason = null;
        if (req != null && req.reason() != null) {
            try {
                reason = CancelReason.valueOf(req.reason());
            } catch (IllegalArgumentException e) {
                reason = null;
            }
        }
        if (reason == null || !reason.allowedFor(requester)) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "reason: 고를 수 있는 취소 사유가 아닙니다");
        }
        if (reason.needsDetail()) {
            String detail = req.reasonDetail() == null ? "" : req.reasonDetail().trim();
            if (detail.isEmpty()) {
                throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "reasonDetail: 기타 사유를 입력해 주세요");
            }
            if (detail.length() > REASON_DETAIL_MAX) {
                throw new BusinessException(OrderErrorCode.VALIDATION_ERROR,
                        "reasonDetail: " + REASON_DETAIL_MAX + "자 이하여야 합니다");
            }
        }
        return reason;
    }

    private TossCancelResult callToss(Start start) {
        try {
            return toss.cancel(start.paymentKey(), start.cancelReason(), start.tossKey());
        } catch (RuntimeException e) {
            // 클라이언트는 예외를 던지지 않게 만들었지만, 혹시 나도 취소 여부를 모르므로 결과 불명으로 둔다
            log.error("토스 취소 호출 중 예외 refundId={}", start.refundId(), e);
            return TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, e.getClass().getSimpleName());
        }
    }

    /** 결과 반영. 그사이 결과 확인이 먼저 바꿨으면 손대지 않는다 */
    private CancelResponse apply(Long orderId, Long refundId, TossCancelResult result) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElseThrow();
        Refund refund = refundRepository.findById(refundId).orElseThrow();
        if (refund.getStatus() != RefundStatus.REQUESTED) {
            log.warn("환불 결과를 반영하려는데 이미 처리 중이 아니다 orderId={} refundStatus={} outcome={}",
                    orderId, refund.getStatus(), result.outcome());
            return CancelResponse.from(order, refund);
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        switch (result.outcome()) {
            case CANCELED -> {
                resultWriter.completed(order, refund, result.canceledAt() != null ? result.canceledAt() : now, now);
                log.info("환불 완료 orderId={} amount={}", orderId, refund.getAmount());
            }
            case DEFINITE_FAILURE -> {
                resultWriter.failed(order, refund, result.errorCode(), result.errorMessage());
                log.warn("환불 실패 orderId={} code={} message={}", orderId, result.errorCode(), result.errorMessage());
            }
            default -> {
                // 결과 불명·이미 취소됨: 오류 하나로 실패를 확정하지 않고 조회로 실제 결과를 확인한다
                resultWriter.checking(order, refund, result.errorCode(), result.errorMessage(), now);
                log.warn("환불 결과 불명. 확인 중으로 두고 조회한다 orderId={} outcome={} code={} message={}",
                        orderId, result.outcome(), result.errorCode(), result.errorMessage());
            }
        }
        return CancelResponse.from(order, refund);
    }

    /** 고객이면 storeId가 null, 점주면 userId가 X-User-Id(없으면 null) */
    private record Requester(CancelRequester type, String userId, Long storeId) {
    }

    private record Start(Long refundId, String paymentKey, String cancelReason, String tossKey) {
    }
}
