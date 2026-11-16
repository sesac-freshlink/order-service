package com.freshlink.order.domain.payment;

import com.freshlink.order.domain.order.OrderStatus;
import io.swagger.v3.oas.annotations.media.Schema;

/** OP-01 응답. tossStatus는 조회에 실패하면 null */
@Schema(description = "OP-01 응답. tossStatus는 조회에 실패하면 null")
public record PaymentSyncResponse(Long orderId, OrderStatus before, OrderStatus after, String tossStatus) {
}
