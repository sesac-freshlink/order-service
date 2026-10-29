package com.freshlink.order.domain.order;

import com.freshlink.common.idempotency.ProcessedMessages;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import com.freshlink.common.outbox.OutboxWriter;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import java.util.Set;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * inventory-results 쪽지(RESERVED·RESERVE_FAILED)를 주문에 반영한다.
 * 처리 기록(processed_message)과 반영은 한 트랜잭션이다. 예외가 나면 둘 다 롤백되고 SQS가 다시 보낸다(3회 실패 → DLQ)
 * 이미 끝난 주문(EXPIRED·OUT_OF_STOCK·PAYMENT_FAILED)에 RESERVED가 늦게 오면 inventory가 잡은 재고를 RELEASE로 돌려준다
 */
@Service
public class ReservationResultHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationResultHandler.class);

    /** 재고를 쥐고 있으면 안 되는 끝난 상태. 진행 중 주문(결제 대기·승인 중·결제 완료)은 예약이 살아 있어 RELEASE하지 않는다 */
    private static final Set<OrderStatus> CLOSED_WITHOUT_STOCK =
            Set.of(OrderStatus.EXPIRED, OrderStatus.OUT_OF_STOCK, OrderStatus.PAYMENT_FAILED);

    private final ProcessedMessages processedMessages;
    private final OrderRepository orderRepository;
    private final OrderLotRepository orderLotRepository;
    private final OutboxWriter outboxWriter;
    private final String commandsQueue;
    private final Clock clock;

    public ReservationResultHandler(ProcessedMessages processedMessages, OrderRepository orderRepository,
                                    OrderLotRepository orderLotRepository, OutboxWriter outboxWriter,
                                    @Value("${app.queue.commands}") String commandsQueue, Clock clock) {
        this.processedMessages = processedMessages;
        this.orderRepository = orderRepository;
        this.orderLotRepository = orderLotRepository;
        this.outboxWriter = outboxWriter;
        this.commandsQueue = commandsQueue;
        this.clock = clock;
    }

    @Transactional
    public void handle(BaseMessage message) {
        if (!(message instanceof ReservedMessage) && !(message instanceof ReserveFailedMessage)) {
            log.warn("inventory-results에 다른 종류의 쪽지가 왔다. 무시한다 type={} messageId={}",
                    message.type(), message.messageId());
            return;
        }
        if (!processedMessages.markIfNew(message.messageId())) {
            log.info("이미 처리한 쪽지 type={} messageId={}", message.type(), message.messageId());
            return;
        }
        if (message instanceof ReservedMessage reserved) {
            onReserved(reserved);
        } else if (message instanceof ReserveFailedMessage failed) {
            onReserveFailed(failed);
        }
    }

    private void onReserved(ReservedMessage m) {
        Order order = lockOrder(m.orderId(), m);
        if (!isReserving(order, m)) {
            releaseIfClosed(order, m);
            return;
        }
        validate(order, m);
        order.reserve(m.productName(), m.unitPrice(), OffsetDateTime.now(clock));
        orderLotRepository.saveAll(m.lots().stream()
                .map(lot -> OrderLot.of(order.getId(), lot.lotId(), lot.quantity(), lot.expiryDate()))
                .toList());
        log.info("재고 확보 orderId={} amount={} lots={} status={}",
                order.getId(), order.getAmount(), m.lots().size(), order.getStatus());
    }

    private void onReserveFailed(ReserveFailedMessage m) {
        Order order = lockOrder(m.orderId(), m);
        if (!isReserving(order, m)) {
            return;
        }
        OrderFailureReason reason = OrderFailureReason.valueOf(m.reason().name());
        order.rejectReservation(reason, m.availableQuantity(), OffsetDateTime.now(clock));
        log.info("재고 확보 실패 orderId={} reason={} available={}", order.getId(), reason, m.availableQuantity());
    }

    /** 없는 주문이면 예외: 큐 접두사가 다르거나 버그다. DLQ로 보내 개발자가 본다 */
    private Order lockOrder(Long orderId, BaseMessage m) {
        return orderRepository.findByIdForUpdate(orderId).orElseThrow(() -> new IllegalStateException(
                "없는 주문의 예약 결과다: orderId=" + orderId + " type=" + m.type() + " messageId=" + m.messageId()));
    }

    /** 이미 다른 상태로 넘어간 주문(수동 종료 등)이면 무시하고 로그만 남긴다 */
    private boolean isReserving(Order order, BaseMessage m) {
        if (order.getStatus() == OrderStatus.RESERVING) {
            return true;
        }
        log.warn("예약 중이 아닌 주문의 결과라 무시한다 orderId={} status={} type={} messageId={}",
                order.getId(), order.getStatus(), m.type(), m.messageId());
        return false;
    }

    /**
     * 끝난 주문에 늦게 온 RESERVED: inventory가 방금 재고를 잡았으니 RELEASE로 돌려준다 (예: 수동 종료 뒤 DLQ의 RESERVE 재처리).
     * 예약이 없으면 inventory가 무시하므로 한 번 더 가도 안전하다(10/15 합의). 사유는 기존 두 가지만 쓴다
     */
    private void releaseIfClosed(Order order, ReservedMessage m) {
        if (!CLOSED_WITHOUT_STOCK.contains(order.getStatus())) {
            return;
        }
        ReleaseReason reason = order.getStatus() == OrderStatus.PAYMENT_FAILED
                ? ReleaseReason.PAYMENT_FAILED : ReleaseReason.EXPIRED;
        outboxWriter.save(commandsQueue, ReleaseMessage.of(order.getId(), order.getStoreId(), reason));
        log.warn("끝난 주문에 늦게 온 RESERVED라 잡힌 재고를 RELEASE한다 orderId={} status={} reason={} messageId={}",
                order.getId(), order.getStatus(), reason, m.messageId());
    }

    /** 주문과 맞지 않는 RESERVED는 반영하지 않는다 (명세 6장) */
    private void validate(Order order, ReservedMessage m) {
        List<ReservedLot> lots = m.lots();
        int lotSum = lots.stream().mapToInt(ReservedLot::quantity).sum();
        boolean valid = order.getStoreId().equals(m.storeId())
                && order.getProductId().equals(m.productId())
                && order.getQuantity() == m.quantity()
                && !lots.isEmpty()
                && lots.stream().allMatch(lot -> lot.lotId() != null && lot.quantity() > 0 && lot.expiryDate() != null)
                && lotSum == m.quantity()
                && m.unitPrice() >= 0
                && m.productName() != null && !m.productName().isBlank();
        if (!valid) {
            throw new IllegalStateException("주문과 맞지 않는 RESERVED다: orderId=" + order.getId()
                    + " messageId=" + m.messageId() + " storeId=" + m.storeId() + " productId=" + m.productId()
                    + " quantity=" + m.quantity() + " lotSum=" + lotSum + " lots=" + lots.size());
        }
    }
}
