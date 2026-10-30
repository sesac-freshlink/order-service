package com.freshlink.order.domain.refund;

/**
 * 결제 후 취소 사유 (추가 명세 A8). 고객과 점주가 고를 수 있는 사유가 다르고, 기타는 둘 다 고를 수 있다.
 * 기타는 상세 입력이 필수다. label은 토스 cancelReason과 화면에 쓴다
 */
public enum CancelReason {
    CHANGE_OF_MIND(CancelRequester.CUSTOMER, "단순 변심"),
    WRONG_SELECTION(CancelRequester.CUSTOMER, "상품·수량 선택 실수"),
    HARD_TO_VISIT(CancelRequester.CUSTOMER, "방문 어려움"),
    OUT_OF_STOCK(CancelRequester.OWNER, "실제 재고 부족"),
    BAD_CONDITION(CancelRequester.OWNER, "상품 상태 불량"),
    STORE_ISSUE(CancelRequester.OWNER, "매장 사정"),
    ETC(null, "기타");

    private final CancelRequester only;
    private final String label;

    CancelReason(CancelRequester only, String label) {
        this.only = only;
        this.label = label;
    }

    /** 이 요청자가 고를 수 있는 사유인지 */
    public boolean allowedFor(CancelRequester requester) {
        return only == null || only == requester;
    }

    public boolean needsDetail() {
        return this == ETC;
    }

    public String label() {
        return label;
    }
}
