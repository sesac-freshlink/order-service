package com.freshlink.order.domain.order;

import io.swagger.v3.oas.annotations.media.Schema;

/** OP-02 응답 */
@Schema(description = "OP-02 응답")
public record OrderCloseResponse(Long orderId, OrderStatus before, OrderStatus after) {
}
