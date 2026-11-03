package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.refund.Refund;
import com.freshlink.order.domain.refund.RefundResponse;
import com.freshlink.order.domain.refund.RefundStatus;
import java.time.OffsetDateTime;
import java.time.ZoneId;

/**
 * OR-02 주문 조회 응답. 시각은 모두 +09:00으로 바꿔 내린다.
 * DB에서 읽은 OffsetDateTime은 JVM 기본 시간대(파드는 UTC일 수 있다)로 오기 때문이다.
 * serverTime은 응답을 만든 서버 현재 시각이다. 화면은 기기 시계와의 차이를 보정해 결제 남은 시간을 센다.
 * refundStatus·refund는 결제 후 취소·환불 표시(추가 명세 A8)다. refund는 마지막 시도이고 없으면 null
 */
public record OrderResponse(
        Long orderId,
        String orderNo,
        OrderStatus status,
        OrderFailureReason failureReason,
        Long storeId,
        String storeName,
        Long productId,
        String productName,
        int quantity,
        Long unitPrice,
        Long amount,
        OffsetDateTime paymentExpiresAt,
        OffsetDateTime paidAt,
        OffsetDateTime createdAt,
        OffsetDateTime pickupDeadline,
        OffsetDateTime pickedUpAt,
        OrderPaymentResponse payment,
        RefundStatus refundStatus,
        RefundResponse refund,
        OffsetDateTime serverTime
) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");

    /** payment는 그 주문의 결제 기록(없으면 null), now는 서버 현재 시각. 환불 시도가 없는 주문 */
    public static OrderResponse from(Order order, Payment payment, OffsetDateTime now) {
        return from(order, payment, null, now);
    }

    /** refund는 그 주문의 마지막 환불 시도(없으면 null) */
    public static OrderResponse from(Order order, Payment payment, Refund refund, OffsetDateTime now) {
        return new OrderResponse(order.getId(), order.getOrderNo(), order.getStatus(), order.getFailureReason(),
                order.getStoreId(), order.getStoreName(), order.getProductId(), order.getProductName(),
                order.getQuantity(), order.getUnitPrice(), order.getAmount(),
                kst(order.getPaymentExpiresAt()), kst(order.getPaidAt()), kst(order.getCreatedAt()),
                kst(order.getPickupDeadline()), kst(order.getPickedUpAt()),
                OrderPaymentResponse.from(payment), order.getRefundStatus(), RefundResponse.from(refund), kst(now));
    }

    private static OffsetDateTime kst(OffsetDateTime time) {
        return time == null ? null : time.atZoneSameInstant(KST).toOffsetDateTime();
    }
}
