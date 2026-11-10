package com.freshlink.order.domain.pickup;

import com.freshlink.order.domain.order.Order;
import com.freshlink.order.domain.order.OrderStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 표의 고객이 이 점포에서 받아 갈 주문(픽업 준비 완료, 환불 NONE, 기한 이내. 11/10부터 준비 완료만), 결제 시각 오래된 순.
 * 승인할 때 쓰는 표를 같이 돌려준다(코드로 찾은 경우에도). 고객 정보는 내리지 않는다
 */
public record PickupPassOrdersResponse(String token, List<Item> orders) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 주문 한 줄. 픽업 준비 완료만 selectable */
    public record Item(Long orderId, String orderNo, OrderStatus status, String productName, int quantity, Long amount,
                       OffsetDateTime paidAt, OffsetDateTime pickupDeadline, boolean selectable) {

        static Item from(Order o) {
            return new Item(o.getId(), o.getOrderNo(), o.getStatus(), o.getProductName(), o.getQuantity(), o.getAmount(),
                    kst(o.getPaidAt()), kst(o.getPickupDeadline()), o.getStatus() == OrderStatus.READY_FOR_PICKUP);
        }
    }

    private static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
