package com.freshlink.order.common.error;

import com.freshlink.common.error.ErrorCode;

/**
 * order-service 오류 목록. 응답 code는 enum 이름이다.
 * 추가할 땐 여기에 한 줄만 넣는다: {@code XXX(404, "메시지")}. 핸들러는 안 건드린다.
 */
public enum OrderErrorCode implements ErrorCode {

    // 순서·이름·문구는 order-service API 명세 8장과 같다
    VALIDATION_ERROR(400, "idempotencyKey: UUID 형식이어야 합니다"),
    ORDER_NO_MISMATCH(400, "주문 정보가 일치하지 않습니다"),
    PAYMENT_AMOUNT_MISMATCH(400, "결제 금액이 일치하지 않습니다"),
    FORBIDDEN(403, "권한이 없습니다"),
    ORDER_ACCESS_DENIED(403, "본인 주문만 볼 수 있습니다"),
    STORE_NOT_LINKED(403, "연결된 점포가 없습니다"),
    ORDER_NOT_FOUND(404, "주문이 없습니다"),
    IDEMPOTENCY_KEY_REUSED(409, "같은 요청 번호로 다른 주문을 만들 수 없습니다"),
    ORDER_NOT_PAYABLE(409, "결제할 수 없는 주문입니다"),
    ORDER_EXPIRED(409, "결제 시간이 지났습니다"),
    PAYMENT_INFO_MISMATCH(409, "이미 다른 결제로 처리 중입니다"),
    ORDER_NOT_SYNCABLE(409, "결제 확인 대상이 아닙니다"),
    ORDER_NOT_CLOSABLE(409, "종료할 수 없는 주문입니다");

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
