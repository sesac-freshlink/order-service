package com.freshlink.order.common.messaging;

import com.freshlink.common.message.MessageJson;
import com.freshlink.order.domain.order.ReservationResultHandler;
import io.awspring.cloud.sqs.annotation.SqsListener;
import org.springframework.stereotype.Component;

/**
 * inventory-results 큐(RESERVED·RESERVE_FAILED)를 받는다. 처리가 끝나야 쪽지를 지운다(기본 ON_SUCCESS).
 * 예외가 나면 쪽지가 남아 SQS가 다시 보낸다
 */
@Component
public class InventoryResultListener {

    private final ReservationResultHandler handler;

    public InventoryResultListener(ReservationResultHandler handler) {
        this.handler = handler;
    }

    @SqsListener("${app.queue.results}")
    public void onResult(String body) {
        handler.handle(MessageJson.fromJson(body));
    }
}
