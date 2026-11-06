package com.freshlink.order.domain.pickup;

import java.time.OffsetDateTime;

/** 고객 QR: QR 안에 넣는 표, 손으로 넣는 6자리 코드, 만료 시각(+09:00), 기한이 남은 미픽업 주문 수 */
public record PickupPassResponse(String token, String code, OffsetDateTime expiresAt, int orderCount) {
}
