package com.freshlink.order.domain.order;

import com.freshlink.common.message.notification.NotificationMessage;
import com.freshlink.common.outbox.OutboxWriter;
import java.time.OffsetDateTime;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

/**
 * 픽업 신청 반영과 점주 새 픽업 주문 알림(NEW_ORDER)을 한곳에 둔다 (11/10 경우 결정: 알림은 결제 때가 아니라 신청 때).
 * 고객 신청(OR 픽업 신청), 마감세일이 섞인 주문의 결제, 시뮬레이터 결제가 같이 쓴다. 부르는 쪽이 주문 행을 잠근 트랜잭션 안에서 부른다
 */
@Component
public class PickupRequests {

    private static final Logger log = LoggerFactory.getLogger(PickupRequests.class);

    private final OutboxWriter outboxWriter;
    private final String notificationsQueue;

    public PickupRequests(OutboxWriter outboxWriter, @Value("${app.queue.notifications}") String notificationsQueue) {
        this.outboxWriter = outboxWriter;
        this.notificationsQueue = notificationsQueue;
    }

    /** 픽업 신청: PICKUP_REQUESTED로 바꾸고 기한을 당기고 점주 알림을 Outbox에 남긴다 */
    public void request(Order order, OffsetDateTime now) {
        order.requestPickup(now);
        outboxWriter.save(notificationsQueue, NotificationMessage.newOrder(order.getStoreId(), order.getId(),
                order.getOrderNo(), order.getProductId(), order.getProductName(), order.getQuantity()));
    }

    /**
     * 결제 직후 자동 신청(마감세일이 섞인 주문, 시뮬레이터 결제). 신청을 받을 수 없으면(결제 결과를 늦게 확인해 기한이 지난 경우 등)
     * 결제 완료로 두고 false. 그 주문은 기한 스케줄러가 닫는다
     */
    public boolean requestIfPossible(Order order, OffsetDateTime now) {
        if (!order.canRequestPickup(now)) {
            log.warn("결제 뒤 자동 픽업 신청을 하지 못했다 orderId={} status={}", order.getId(), order.getStatus());
            return false;
        }
        request(order, now);
        return true;
    }
}
