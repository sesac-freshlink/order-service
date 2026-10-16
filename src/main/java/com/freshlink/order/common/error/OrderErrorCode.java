package com.freshlink.order.common.error;

import com.freshlink.common.error.ErrorCode;

/**
 * order-service 오류 목록. 응답 code는 enum 이름이다.
 * 추가할 땐 여기에 한 줄만 넣는다: {@code XXX(404, "메시지")}. 핸들러는 안 건드린다.
 */
public enum OrderErrorCode implements ErrorCode {

    ORDER_NOT_FOUND(404, "주문이 없습니다"),
    ORDER_FORBIDDEN(403, "내 주문이 아닙니다"),
    INVALID_ORDER_STATUS(409, "지금 상태에서는 할 수 없는 작업입니다"),
    INVALID_QUANTITY(400, "수량이 올바르지 않습니다"),
    OUT_OF_STOCK(409, "재고가 부족합니다"),
    PAYMENT_FAILED(402, "결제에 실패했습니다"),
    PAYMENT_AMOUNT_MISMATCH(400, "결제 금액이 주문 금액과 다릅니다"),
    SERVICE_UNAVAILABLE(503, "잠시 후 다시 시도해 주세요");

    private final int status;
    private final String message;

    OrderErrorCode(int status, String message) {
        this.status = status;
        this.message = message;
    }

    @Override
    public int status() {
        return status;
    }

    @Override
    public String message() {
        return message;
    }
}
