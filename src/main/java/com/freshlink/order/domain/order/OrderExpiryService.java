package com.freshlink.order.domain.order;

import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.outbox.OutboxWriter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * 결제 대기 10분 만료 (명세 5장). PAYMENT_WAITING이고 마감이 지난 주문만 EXPIRED + RELEASE(EXPIRED).
 * 주문마다 트랜잭션을 따로 둔다: 행을 잠근 뒤 상태·마감을 다시 확인하므로 같은 순간의 결제 승인(OR-03)과
 * 둘 다 확정되지 않는다. PAYMENT_APPROVING은 건드리지 않는다
 */
@Service
public class OrderExpiryService {

    private static final Logger log = LoggerFactory.getLogger(OrderExpiryService.class);
    static final int BATCH_SIZE = 100;

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String commandsQueue;

    public OrderExpiryService(OrderRepository orderRepository, OutboxWriter outboxWriter,
                              TransactionTemplate transactionTemplate, Clock clock,
                              @Value("${app.queue.commands}") String commandsQueue) {
        this.orderRepository = orderRepository;
        this.outboxWriter = outboxWriter;
        this.transactionTemplate = transactionTemplate;
        this.clock = clock;
        this.commandsQueue = commandsQueue;
    }

    public int expireDue() {
        return expireDue(BATCH_SIZE);
    }

    /** 한 번에 limit건까지. 남은 건은 다음 차례(1분 뒤)에 한다. 만료한 건수를 돌려준다 */
    public int expireDue(int limit) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        List<Long> ids = orderRepository.findDueIds(OrderStatus.PAYMENT_WAITING, now, PageRequest.of(0, limit));
        int expired = 0;
        for (Long id : ids) {
            try {
                if (Boolean.TRUE.equals(transactionTemplate.execute(s -> expireOne(id, now)))) {
                    expired++;
                }
            } catch (RuntimeException e) {
                // 한 건이 실패해도 나머지는 계속한다. 실패한 건은 다음 차례에 다시 본다
                log.error("주문 만료 실패 orderId={}", id, e);
            }
        }
        if (expired > 0) {
            log.info("결제 시간 만료 {}건", expired);
        }
        return expired;
    }

    /** 잠근 뒤 다시 확인한다. 그사이 결제 승인이 시작됐으면 건너뛴다 */
    private boolean expireOne(Long orderId, OffsetDateTime now) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || order.getStatus() != OrderStatus.PAYMENT_WAITING
                || order.getPaymentExpiresAt() == null || now.isBefore(order.getPaymentExpiresAt())) {
            return false;
        }
        order.expire(now);
        outboxWriter.save(commandsQueue, ReleaseMessage.of(orderId, order.getStoreId(), ReleaseReason.EXPIRED));
        log.info("주문 만료 orderId={} expiresAt={}", orderId, order.getPaymentExpiresAt());
        return true;
    }
}
