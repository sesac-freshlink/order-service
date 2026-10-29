package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;
import java.time.ZoneId;

/** A5 점주 픽업 확인 응답. 픽업 시각은 +09:00 */
public record PickupResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        OffsetDateTime pickedUpAt
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    public static PickupResponse from(Order order) {
        OffsetDateTime pickedUpAt = order.getPickedUpAt() == null ? null
                : order.getPickedUpAt().atZoneSameInstant(KST).toOffsetDateTime();
        return new PickupResponse(order.getId(), order.getOrderNo(), order.getStatus(), pickedUpAt);
    }
}
