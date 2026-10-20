package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.common.error.OrderErrorCode;
import java.time.Clock;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * OP-02 멈춘 주문 수동 종료. 재고 확보 결과가 오지 않아 RESERVING에 멈춘 주문을 EXPIRED로 닫고
 * RELEASE(EXPIRED)를 같은 트랜잭션에 저장한다. 예약이 없으면 inventory가 무시한다 (이태우님 합의 10/15)
 */
@Service
public class OrderCloseService {

    private static final Logger log = LoggerFactory.getLogger(OrderCloseService.class);

    private final OrderRepository orderRepository;
    private final OutboxWriter outboxWriter;
    private final Clock clock;
    private final String commandsQueue;

    public OrderCloseService(OrderRepository orderRepository, OutboxWriter outboxWriter, Clock clock,
                             @Value("${app.queue.commands}") String commandsQueue) {
        this.orderRepository = orderRepository;
        this.outboxWriter = outboxWriter;
        this.clock = clock;
        this.commandsQueue = commandsQueue;
    }

    @Transactional
    public OrderCloseResponse close(Long orderId) {
        Order order = orderRepository.findByIdForUpdate(orderId)
                .orElseThrow(() -> new BusinessException(OrderErrorCode.ORDER_NOT_FOUND));
        if (order.getStatus() != OrderStatus.RESERVING) {
            throw new BusinessException(OrderErrorCode.ORDER_NOT_CLOSABLE);
        }
        order.closeStuck(OffsetDateTime.now(clock));
        outboxWriter.save(commandsQueue, ReleaseMessage.of(orderId, order.getStoreId(), ReleaseReason.EXPIRED));
        log.warn("멈춘 주문 수동 종료 orderId={} (RESERVE가 DLQ에 남아 있으면 다시 보내지 않는다)", orderId);
        return new OrderCloseResponse(orderId, OrderStatus.RESERVING, order.getStatus());
    }
}
