package com.freshlink.order.domain.order;

import java.util.List;

/**
 * 점주 하루 상품별 매출. date는 KST 날짜(yyyy-MM-dd). 많이 팔린 순(수량, 같으면 금액)이다.
 * soldQuantity·clearanceQuantity·netAmount는 그날 결제 승인분에서 그날 환불 완료분을 뺀 값이다
 */
public record SalesProductsResponse(String date, List<Item> items) {

    public record Item(Long productId, String productName, long soldQuantity, long clearanceQuantity,
                       long netAmount) {
    }
}
