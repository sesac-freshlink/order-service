package com.freshlink.order.domain.firstcome;

/** 선착순은 점포마다 따로 켠다(inventory #61). 대기열도 점포·상품 한 쌍마다 하나다 */
public record QueueKey(Long storeId, Long productId) {

    public String value() {
        return storeId + ":" + productId;
    }

    public static QueueKey parse(String value) {
        int colon = value.indexOf(':');
        return new QueueKey(Long.valueOf(value.substring(0, colon)), Long.valueOf(value.substring(colon + 1)));
    }
}
