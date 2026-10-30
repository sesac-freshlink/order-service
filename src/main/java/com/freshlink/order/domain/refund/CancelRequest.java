package com.freshlink.order.domain.refund;

/**
 * 결제 후 취소 요청 본문 (추가 명세 A8). reason은 CancelReason 이름, reasonDetail은 기타일 때 필수(최대 200자).
 * 값 검사는 요청자(고객·점주)에 따라 달라 RefundService에서 한다
 */
public record CancelRequest(String reason, String reasonDetail) {
}
