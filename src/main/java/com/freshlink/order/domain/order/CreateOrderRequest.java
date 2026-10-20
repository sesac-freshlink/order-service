package com.freshlink.order.domain.order;

import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** OR-01 주문 생성 요청 */
public record CreateOrderRequest(
        @NotNull Long storeId,
        @NotBlank @Size(max = 30) String storeName,
        @NotNull Long productId,
        @NotNull @Min(1) @Max(5) Integer quantity
) {
}
