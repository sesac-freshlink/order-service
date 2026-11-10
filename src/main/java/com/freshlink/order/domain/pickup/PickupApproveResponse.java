package com.freshlink.order.domain.pickup;

import java.util.List;

/** 일괄 픽업 승인 결과. 주문마다 따로 처리한다 */
public record PickupApproveResponse(List<Result> results) {

    /**
     * PICKED_UP 픽업 완료, NOT_PICKABLE 픽업 준비 완료가 아니거나 이미 처리됨, DEADLINE_PASSED 기한 지남,
     * NOT_FOUND 없는 주문이거나 이 고객·이 점포 주문이 아님
     */
    public enum Outcome { PICKED_UP, NOT_PICKABLE, DEADLINE_PASSED, NOT_FOUND }

    public record Result(Long orderId, Outcome result) {
    }
}
