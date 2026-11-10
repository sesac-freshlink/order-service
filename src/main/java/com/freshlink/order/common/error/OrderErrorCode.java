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
    STORE_ORDER_ACCESS_DENIED(403, "우리 점포 주문만 처리할 수 있습니다"),
    STORE_NOT_LINKED(403, "연결된 점포가 없습니다"),
    ORDER_NOT_FOUND(404, "주문이 없습니다"),
    IDEMPOTENCY_KEY_REUSED(409, "같은 요청 번호로 다른 주문을 만들 수 없습니다"),
    ORDER_NOT_PAYABLE(409, "결제할 수 없는 주문입니다"),
    ORDER_EXPIRED(409, "결제 시간이 지났습니다"),
    PAYMENT_INFO_MISMATCH(409, "이미 다른 결제로 처리 중입니다"),
    ORDER_NOT_SYNCABLE(409, "결제 확인 대상이 아닙니다"),
    ORDER_NOT_CLOSABLE(409, "종료할 수 없는 주문입니다"),
    ORDER_NOT_PICKABLE(409, "픽업할 수 없는 주문입니다"),
    PICKUP_DEADLINE_PASSED(409, "픽업 기한이 지난 주문입니다"),
    // A7 결제 전 취소·A8·A9 결제 후 취소·환불 (API 명세에 아직 없음, 추가 명세 A7~A9)
    ORDER_NOT_CANCELABLE(409, "취소할 수 없는 주문입니다"),
    REFUND_IN_PROGRESS(409, "취소·환불을 처리하고 있습니다"),
    // N-02 픽업 준비 완료 (API 명세에 아직 없음)
    ORDER_NOT_READYABLE(409, "픽업 준비 완료로 바꿀 수 없는 주문입니다"),
    ORDER_NOT_PICKUP_REQUESTABLE(409, "픽업 신청할 수 없는 주문입니다"),
    // 선착순 대기열 (API 명세에 아직 없음, 오픈런 문서 2장)
    FIRST_COME_QUANTITY_LIMIT(400, "선착순 상품은 1개만 주문할 수 있습니다"),
    FIRST_COME_CLEARANCE_NOT_ALLOWED(400, "선착순 상품은 마감세일로 살 수 없어요"),
    CLEARANCE_NOT_ON_SALE(400, "마감세일은 06:00~23:00에만 살 수 있어요"),
    NOT_FIRST_COME(404, "선착순 상품이 아닙니다"),
    FIRST_COME_TICKET_REQUIRED(409, "차례가 오면 주문할 수 있습니다. 대기 화면에서 순서를 기다려 주세요"),
    FIRST_COME_ALREADY_ORDERED(409, "이미 주문한 선착순 상품입니다"),
    FIRST_COME_UNAVAILABLE(503, "선착순 대기열을 지금 쓸 수 없습니다. 잠시 후 다시 시도해 주세요"),
    // 픽업 QR (추가 명세 A16)
    NO_PICKUP_WAITING_ORDER(409, "받아 갈 주문이 없습니다"),
    PICKUP_PASS_NOT_FOUND(404, "QR이 만료됐거나 없습니다. 고객에게 새 QR을 보여 달라고 해 주세요"),
    PICKUP_CODE_TOO_MANY_ATTEMPTS(429, "코드를 너무 많이 틀렸습니다. 1분 뒤 다시 시도해 주세요"),
    PICKUP_PASS_UNAVAILABLE(503, "픽업 QR을 지금 쓸 수 없습니다. 잠시 후 다시 시도해 주세요");

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
