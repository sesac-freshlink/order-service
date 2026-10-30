package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.refund.Refund;
import com.freshlink.order.domain.refund.RefundResponse;
import com.freshlink.order.domain.refund.RefundStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OR-04 점주 주문 목록의 한 줄. 고객 정보는 내리지 않고 주문번호로 구분한다. 시각은 +09:00, payment는 결제 내역.
 * status·pickedUpAt은 A6 구분(픽업 완료·기한 경과)에서 쓴다.
 * refundStatus·cancelRequestedAt·refund는 취소·환불 구분과 환불 재시도 버튼(추가 명세 A9)에서 쓴다. refund는 마지막 시도
 */
public record OwnerOrderResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        String productName,
        int quantity,
        Long amount,
        OffsetDateTime paidAt,
        OffsetDateTime pickupDeadline,
        OffsetDateTime pickedUpAt,
        OrderPaymentResponse payment,
        RefundStatus refundStatus,
        OffsetDateTime cancelRequestedAt,
        RefundResponse refund
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** 환불 시도가 없는 주문 (MVP 신규 주문 목록은 환불 NONE만 나온다) */
    public static OwnerOrderResponse from(Order order, Payment payment) {
        return from(order, payment, null);
    }

    public static OwnerOrderResponse from(Order order, Payment payment, Refund refund) {
        return new OwnerOrderResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getProductName(),
                order.getQuantity(), order.getAmount(), kst(order.getPaidAt()), kst(order.getPickupDeadline()),
                kst(order.getPickedUpAt()), OrderPaymentResponse.from(payment), order.getRefundStatus(),
                kst(order.getCancelRequestedAt()), RefundResponse.from(refund));
    }

    private static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
