package com.freshlink.order.domain.order;

import jakarta.validation.constraints.AssertTrue;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/**
 * OR-01 주문 생성 요청. quantity는 전체 수량(1~5), clearanceQuantity는 그중 마감세일 수량(없으면 0, 마감세일 함께 사기)
 */
public record CreateOrderRequest(
        @NotNull Long storeId,
        @NotBlank @Size(max = 30) String storeName,
        @NotNull Long productId,
        @NotNull @Min(1) @Max(5) Integer quantity,
        @Min(0) @Max(5) Integer clearanceQuantity
) {

    public CreateOrderRequest {
        clearanceQuantity = clearanceQuantity == null ? 0 : clearanceQuantity;
    }

    /** 마감세일 없는 주문 */
    public CreateOrderRequest(Long storeId, String storeName, Long productId, Integer quantity) {
        this(storeId, storeName, productId, quantity, 0);
    }

    /** 마감세일 수량은 전체 수량을 넘을 수 없다 */
    @AssertTrue(message = "전체 수량 이하여야 합니다")
    public boolean isClearanceQuantityWithinQuantity() {
        return quantity == null || clearanceQuantity <= quantity;
    }
}
