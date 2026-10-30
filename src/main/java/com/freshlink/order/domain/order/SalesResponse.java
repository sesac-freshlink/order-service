package com.freshlink.order.domain.order;

import java.util.List;

/**
 * 점주 매출 (GET /owner/orders/sales). 묶음은 기간 안의 모든 일·주·월을 빠짐없이 오래된 순으로 내리고, 매출이 없는 묶음은 0이다.
 * 결제는 토스 승인 시각(approvedAt), 환불은 환불 시각 기준 KST 날짜로 넣는다
 */
public record SalesResponse(String from, String to, String unit, List<Period> periods) {

    /**
     * 한 묶음. netAmount = paidAmount - refundAmount.
     * canceledCount는 결제 전 고객 취소(A7) 건수다. 환불(A8·A9)이 생기기 전까지 refundAmount·refundCount는 0이다
     */
    public record Period(String period, long paidAmount, long refundAmount, long netAmount,
                         long paidCount, long refundCount, long canceledCount) {
    }
}
