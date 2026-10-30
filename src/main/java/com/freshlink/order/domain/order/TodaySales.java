package com.freshlink.order.domain.order;

/** 매출 SSE로 보내는 오늘(KST) 합계. date는 yyyy-MM-dd, 승인 완료(DONE) 결제만 센다 */
public record TodaySales(String date, long paidAmount, long paidCount) {
}
