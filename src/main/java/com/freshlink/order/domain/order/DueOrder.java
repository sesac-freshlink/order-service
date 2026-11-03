package com.freshlink.order.domain.order;

import java.time.OffsetDateTime;

/**
 * 스케줄러가 꺼내는 마감 지난 주문 한 줄. due 는 결제 마감(결제 만료) 또는 픽업 기한(픽업 기한 경과).
 * (due, id) 순서로 꺼내고, 한 차례 안에서 "방금 본 마지막 줄 다음부터" 이어서 꺼낼 때 기준으로 쓴다
 */
public record DueOrder(Long id, OffsetDateTime due) {
}
