package com.freshlink.order.domain.order;

/** 결제가 완료돼 그 점포의 매출이 바뀌었다. 트랜잭션이 커밋된 뒤 매출 SSE가 받는다 */
public record SalesChangedEvent(Long storeId) {
}
