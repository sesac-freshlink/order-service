package com.freshlink.order.domain.pickup;

/** 점주 핸드폰 스캔 결과: 알림을 받은 점주 컴퓨터 화면 수. 0이면 열린 화면이 없다 */
public record PickupScanResponse(int screens) {
}
