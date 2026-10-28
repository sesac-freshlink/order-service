package com.freshlink.order.domain.order;

import com.freshlink.common.idempotency.ProcessedMessages;
import com.freshlink.common.message.BaseMessage;
import com.freshlink.common.message.result.ReserveFailedMessage;
import com.freshlink.common.message.result.ReservedLot;
import com.freshlink.common.message.result.ReservedMessage;
import java.time.Clock;
import java.time.OffsetDateTime;
import java.util.List;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * inventory-results 쪽지(RESERVED·RESERVE_FAILED)를 주문에 반영한다.
 * 처리 기록(processed_message)과 반영은 한 트랜잭션이다. 예외가 나면 둘 다 롤백되고 SQS가 다시 보낸다(3회 실패 → DLQ)
 */
@Service
public class ReservationResultHandler {

    private static final Logger log = LoggerFactory.getLogger(ReservationResultHandler.class);

    private final ProcessedMessages processedMessages;
    private final OrderRepository orderRepository;
    private final OrderLotRepository orderLotRepository;
    private final Clock clock;

    public ReservationResultHandler(ProcessedMessages processedMessages, OrderRepository orderRepository,
                                    OrderLotRepository orderLotRepository, Clock clock) {
        this.processedMessages = processedMessages;
        this.orderRepository = orderRepository;
        this.orderLotRepository = orderLotRepository;
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
