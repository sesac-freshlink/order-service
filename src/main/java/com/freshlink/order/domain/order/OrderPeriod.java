package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;

/**
 * A3·A6 기간 검색 (멘토링 후 할 일 "기간·상태 검색을 더한 주문 이력 조회"). 주문 만든 시각(created_at) 기준, KST 날짜, from·to 둘 다 포함.
 * 비우면 그쪽 끝은 열려 있다. 형식이 yyyy-MM-dd가 아니거나 from이 to보다 늦으면 400 VALIDATION_ERROR
 */
public record OrderPeriod(OffsetDateTime start, OffsetDateTime end) {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 비운 쪽의 끝. MySQL DATETIME 범위 안이고 실제 주문 시각과 겹치지 않는 값 */
    private static final OffsetDateTime OPEN_START = LocalDate.of(2000, 1, 1).atStartOfDay(KST).toOffsetDateTime();
    private static final OffsetDateTime OPEN_END = LocalDate.of(9000, 1, 1).atStartOfDay(KST).toOffsetDateTime();

    /** start 이상, end 미만 */
    public static OrderPeriod of(String from, String to) {
        LocalDate f = date("from", from);
        LocalDate t = date("to", to);
        if (f != null && t != null && f.isAfter(t)) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "from: to보다 늦을 수 없습니다");
        }
        return new OrderPeriod(f == null ? OPEN_START : f.atStartOfDay(KST).toOffsetDateTime(),
                t == null ? OPEN_END : t.plusDays(1).atStartOfDay(KST).toOffsetDateTime());
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            return null;
        }
        try {
            return LocalDate.parse(value);
        } catch (DateTimeParseException e) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, name + ": yyyy-MM-dd 형식이어야 합니다");
        }
    }
}
