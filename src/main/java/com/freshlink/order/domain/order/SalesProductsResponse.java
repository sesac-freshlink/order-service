package com.freshlink.order.domain.order;

import io.swagger.v3.oas.annotations.media.Schema;
import java.util.List;

/**
 * 점주 하루 상품별 매출. date는 KST 날짜(yyyy-MM-dd). 많이 팔린 순(수량, 같으면 금액)이다.
 * soldQuantity·clearanceQuantity·netAmount는 그날 결제 승인분에서 그날 환불 완료분을 뺀 값이다
 */
@Schema(description = "점주 하루 상품별 매출. date는 KST 날짜(yyyy-MM-dd). 많이 팔린 순(수량, 같으면 금액)이다. soldQuantity·clearanceQuantity·netAmount는 그날 결제 승인분에서 그날 환불 완료분을 뺀 값이다")
public record SalesProductsResponse(String date, List<Item> items) {

    /** 상품 하나의 그날 판매 수량·마감세일 수량·실제 결제 금액 */
    @Schema(name = "SalesProductItem", description = "상품 하나의 그날 판매 수량·마감세일 수량·실제 결제 금액")
    public record Item(Long productId, String productName, long soldQuantity, long clearanceQuantity,
                       long netAmount) {
    }
}
