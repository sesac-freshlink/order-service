package com.freshlink.order.domain.refund;

import com.freshlink.common.message.command.ReturnMessage;
import com.freshlink.common.outbox.OutboxWriter;
import com.freshlink.order.domain.order.Order;
import java.time.OffsetDateTime;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 환불 결과를 주문·환불 시도·Outbox에 함께 반영한다. 취소 요청과 결과 확인 스케줄러가 같이 쓴다.
 * 부르는 쪽이 주문 행을 잠근 트랜잭션 안에서 부른다
 */
@Component
public class RefundResultWriter {

    private final OutboxWriter outboxWriter;
    private final String commandsQueue;

    public RefundResultWriter(OutboxWriter outboxWriter, @Value("${app.queue.commands}") String commandsQueue) {
        this.outboxWriter = outboxWriter;
        this.commandsQueue = commandsQueue;
    }

    /** A9·A10 환불 완료: REFUNDED + RETURN(재고 복원)을 한 트랜잭션에 저장한다. 복원이 늦어도 환불 완료는 그대로다 */
    public void completed(Order order, Refund refund, OffsetDateTime canceledAt, OffsetDateTime now) {
        order.completeRefund(canceledAt, now);
        refund.complete(canceledAt);
        outboxWriter.save(commandsQueue, ReturnMessage.of(order.getId(), order.getStoreId()));
    }

    /** A9 명확한 환불 실패: 재신청·재시도를 받는다 */
    public void failed(Order order, Refund refund, String errorCode, String errorMessage) {
        order.failRefund();
        refund.fail(errorCode, errorMessage);
    }

    /** A9 환불 결과 불명: 확인 중으로 두고 곧바로 조회를 예약한다 */
    public void checking(Order order, Refund refund, String errorCode, String errorMessage, OffsetDateTime now) {
        order.checkRefund();
        refund.check(errorCode, errorMessage, now);
    }
}
