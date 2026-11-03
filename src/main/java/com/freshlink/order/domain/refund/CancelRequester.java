package com.freshlink.order.domain.refund;

/** 취소를 요청한 쪽 (추가 명세 A8). 주문 상세에 요청자로 보여 준다 */
public enum CancelRequester {
    CUSTOMER,
    OWNER
}
