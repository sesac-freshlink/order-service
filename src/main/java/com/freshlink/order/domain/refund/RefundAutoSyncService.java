package com.freshlink.order.domain.refund;

import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderRepository;
import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentRepository;
import com.freshlink.order.domain.payment.toss.TossCancelOutcome;
import com.freshlink.order.domain.payment.toss.TossCancelResult;
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
 * A9 환불 결과 확인. 결과 불명으로 CHECKING에 머문 환불을 서버가 토스 결제 조회로 확인한다.
 * 서버가 멈췄다 켜져도 예약(next_sync_at)이 DB에 있어 이어서 확인한다(추가 명세 A9).
 * 결제 결과 불명(A11)과 같은 원칙·횟수: 곧바로 첫 조회, 이후 30초마다 추가 10번.
 * 결제가 CANCELED면 완료, 한도까지 계속 DONE(취소 안 됨)이면 최종 실패로 확정한다.
 * 조회 실패만 이어지면 실패로 정하지 않고 확인 중으로 남겨 경고 로그(수동 확인 대상)를 남긴다.
 * 같은 시도를 새 멱등키로 다시 취소하지 않는다(재시도는 고객·점주의 재신청으로 새 시도를 만든다)
 */
@Service
public class RefundAutoSyncService {

    private static final Logger log = LoggerFactory.getLogger(RefundAutoSyncService.class);
    static final int BATCH_SIZE = 50;
    /** 첫 조회 1번 + 추가 10번 (A11과 같음) */
    static final int MAX_ATTEMPTS = 11;
    static final Duration INTERVAL = Duration.ofSeconds(30);

    private final RefundRepository refundRepository;
    private final OrderRepository orderRepository;
    private final PaymentRepository paymentRepository;
    private final TossPaymentsClient toss;
    private final RefundResultWriter resultWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;

    public RefundAutoSyncService(RefundRepository refundRepository, OrderRepository orderRepository,
                                 PaymentRepository paymentRepository, TossPaymentsClient toss,
                                 RefundResultWriter resultWriter, TransactionTemplate transactionTemplate, Clock clock) {
        this.refundRepository = refundRepository;
        this.orderRepository = orderRepository;
        this.paymentRepository = paymentRepository;
        this.toss = toss;
        this.resultWriter = resultWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
    }

    /** 차례가 된 환불을 조회한다. 조회한 건수를 돌려준다 */
    public int syncDue() {
        if (!toss.isConfigured()) {
            // 키가 없으면 횟수를 쓰지 않고 다음 차례로 미룬다 (연동 설정 문제는 개발자 확인)
            return 0;
        }
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> refundIds = refundRepository.findSyncDueIds(RefundStatus.CHECKING, now, PageRequest.of(0, BATCH_SIZE));
        for (Long refundId : refundIds) {
            Long orderId = null;
            TossCancelResult result;
            try {
                Refund refund = refundRepository.findById(refundId).orElseThrow();
                orderId = refund.getOrderId();
                Payment payment = paymentRepository.findByOrderId(orderId).orElseThrow();
                result = toss.lookupCancel(payment.getPaymentKey());
            } catch (RuntimeException e) {
                // 조회 중 오류도 결과 불명과 같이 다룬다. 횟수를 쓰고 다음에 다시 본다
                log.error("환불 결과 확인 실패 refundId={}", refundId, e);
                result = TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, e.getClass().getSimpleName());
            }
            if (orderId == null) {
                continue;
            }
            Long lockId = orderId;
            TossCancelResult checked = result != null ? result
                    : TossCancelResult.error(TossCancelOutcome.UNKNOWN, null, "빈 결과");
            try {
                transactionTemplate.executeWithoutResult(s -> apply(lockId, refundId, checked, now));
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 계속한다. 다음 차례에 다시 본다
                log.error("환불 결과 반영 실패 refundId={}", refundId, e);
            }
        }
        return refundIds.size();
    }

    /** 주문을 먼저 잠근 뒤 시도를 읽는다. 취소 요청·다른 확인과 겹쳐도 차례대로 바뀐다 */
    private void apply(Long orderId, Long refundId, TossCancelResult result, OffsetDateTime now) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        Refund refund = refundRepository.findById(refundId).orElse(null);
        if (order == null || refund == null) {
            return;
        }
        if (refund.getStatus() != RefundStatus.CHECKING) {
            refund.stopSync();
            return;
        }
        if (result.outcome() == TossCancelOutcome.CANCELED) {
            resultWriter.completed(order, refund, result.canceledAt() != null ? result.canceledAt() : now, now);
            log.info("환불 확인: 취소 성공으로 반영 orderId={}", order.getId());
            return;
        }
        boolean limitReached = refund.afterSyncAttempt(now, INTERVAL, MAX_ATTEMPTS);
        if (!limitReached) {
            return;
        }
        if (result.outcome() == TossCancelOutcome.NOT_CANCELED) {
            resultWriter.failed(order, refund, "NOT_CANCELED", "결과 확인 " + refund.getSyncAttempts() + "번 동안 취소되지 않음");
            log.warn("환불 확인: {}번 조회에도 결제가 취소되지 않아 실패로 확정 orderId={}",
                    refund.getSyncAttempts(), order.getId());
        } else {
            log.warn("환불 결과 확인 {}번에도 결과 불명. 수동 확인 대상 orderId={} refundId={} code={}",
                    refund.getSyncAttempts(), order.getId(), refundId, result.errorCode());
        }
    }
}
