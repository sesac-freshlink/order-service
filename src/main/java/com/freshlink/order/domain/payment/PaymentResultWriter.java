package com.freshlink.order.domain.payment;

import com.freshlink.common.message.command.ConfirmMessage;
import com.freshlink.common.message.command.ReleaseMessage;
import com.freshlink.common.message.command.ReleaseReason;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.domain.order.Order;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 결제 결과를 주문·결제 기록·Outbox에 함께 반영한다. OR-03 승인과 OP-01 수동 확인이 같이 쓴다.
 * 부르는 쪽이 주문 행을 잠근 트랜잭션 안에서 부른다
 */
@Component
public class PaymentResultWriter {

    private final OutboxWriter outboxWriter;
    private final String commandsQueue;

    public PaymentResultWriter(OutboxWriter outboxWriter, @Value("${app.queue.commands}") String commandsQueue) {
        this.outboxWriter = outboxWriter;
        this.commandsQueue = commandsQueue;
    }

    /** PAID + CONFIRM */
    public void paid(Order order, Payment payment, String tossStatus, String method, String receiptUrl,
                     OffsetDateTime paidAt) {
        order.markPaid(paidAt);
        payment.approve(tossStatus, method, paidAt, receiptUrl);
        outboxWriter.save(commandsQueue, ConfirmMessage.of(order.getId(), order.getStoreId()));
    }

    /** PAYMENT_FAILED + RELEASE(PAYMENT_FAILED) */
    public void failed(Order order, Payment payment, String errorCode, String errorMessage, OffsetDateTime now) {
        order.failPayment(now);
        payment.fail(errorCode, errorMessage);
        outboxWriter.save(commandsQueue,
                ReleaseMessage.of(order.getId(), order.getStoreId(), ReleaseReason.PAYMENT_FAILED));
    }
}
