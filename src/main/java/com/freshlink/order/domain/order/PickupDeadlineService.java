package com.freshlink.order.domain.order;

import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.domain.refund.RefundStatus;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.data.domain.PageRequest;
import org.springframework.stereotype.Service;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * A4 픽업 기한 경과 (추가 명세 A4). 기한이 지난 결제 완료·픽업 준비 완료(환불 NONE) 주문은 PICKUP_EXPIRED(쪽지 없음),
 * 결제 대기 주문은 EXPIRED·사유 PICKUP_DEADLINE + RELEASE(EXPIRED).
 * 기한 안에 시작한 환불(처리 중·확인 중·실패)이 있는 주문은 기한이 지나도 바꾸지 않는다(추가 명세 0.4).
 * 주문마다 트랜잭션을 따로 두고, 잠근 뒤 상태·기한을 다시 확인해 픽업 확인·결제 승인·환불 요청과 동시에 확정되지 않게 한다
 */
@Service
public class PickupDeadlineService {

    private static final Logger log = LoggerFactory.getLogger(PickupDeadlineService.class);
    static final int BATCH_SIZE = 100;
    /** 한 차례에 꺼내 보는 묶음 수 상한 (실패가 많아도 한 차례가 끝없이 길어지지 않게) */
    static final int MAX_PAGES = 10;
    private static final Set<OrderStatus> TARGETS =
            Set.of(OrderStatus.PAID, OrderStatus.READY_FOR_PICKUP, OrderStatus.PAYMENT_WAITING);

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;
    private final TransactionTemplate transactionTemplate;
    private final Clock clock;
    private final String commandsQueue;

    public PickupDeadlineService(OrderRepository orderRepository, OutboxWriter outboxWriter,
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

    /**
     * 한 차례에 limit건까지 닫는다. 실패한 건이 있어 limit건을 못 채웠고 꺼낸 묶음이 꽉 찼으면(뒤에 더 있으면)
     * 방금 본 마지막 줄 다음부터 이어서 꺼낸다. 맨 앞 묶음이 계속 실패해도 뒤 주문이 막히지 않게 하려는 것이다.
     * 꺼내 보는 묶음은 MAX_PAGES 번까지. 실패한 건은 이번 차례에만 건너뛰고 다음 차례(1분 뒤)에 다시 본다
     */
    public int expireDue(int limit) {
        OffsetDateTime now = OffsetDateTime.now(clock);
        PageRequest page = PageRequest.of(0, limit);
        List<DueOrder> batch = orderRepository.findPickupDue(TARGETS, RefundStatus.NONE, now, page);
        int closed = 0;
        for (int pages = 1; ; pages++) {
            for (DueOrder due : batch) {
                if (closed == limit) {
                    break;
                }
                try {
                    if (Boolean.TRUE.equals(transactionTemplate.execute(s -> expireOne(due.id(), now)))) {
                        closed++;
                    }
                } catch (RuntimeException e) {
                    // 한 건이 실패해도 나머지는 계속한다. 실패한 건은 다음 차례에 다시 본다
                    log.error("픽업 기한 처리 실패 orderId={}", due.id(), e);
                }
            }
            if (closed == limit || batch.size() < limit || pages == MAX_PAGES) {
                break;
            }
            DueOrder last = batch.get(batch.size() - 1);
            batch = orderRepository.findPickupDueAfter(TARGETS, RefundStatus.NONE, now, last.due(), last.id(), page);
        }
        if (closed > 0) {
            log.info("픽업 기한 경과 {}건", closed);
        }
        return closed;
    }

    /** 잠근 뒤 다시 확인한다. 그사이 픽업 확인·결제 승인·환불 요청이 먼저 됐으면 건너뛴다 */
    private boolean expireOne(Long orderId, OffsetDateTime now) {
        Order order = orderRepository.findByIdForUpdate(orderId).orElse(null);
        if (order == null || !TARGETS.contains(order.getStatus()) || order.getRefundStatus() != RefundStatus.NONE
                || !order.isPickupDeadlinePassed(now)) {
            return false;
        }
        boolean waiting = order.getStatus() == OrderStatus.PAYMENT_WAITING;
        order.expirePickup(now);
        if (waiting) {
            outboxWriter.save(commandsQueue, ReleaseMessage.of(orderId, order.getStoreId(), ReleaseReason.EXPIRED));
        }
        log.info("픽업 기한 경과 orderId={} status={} deadline={}", orderId, order.getStatus(), order.getPickupDeadline());
        return true;
    }
}
