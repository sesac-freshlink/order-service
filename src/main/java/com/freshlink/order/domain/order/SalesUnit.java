package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import java.time.DayOfWeek;
import java.time.LocalDate;
import java.time.format.DateTimeFormatter;
import java.time.temporal.TemporalAdjusters;
import java.util.Locale;

/** 매출 묶음 단위 (GET /owner/orders/sales unit). 주는 월요일에 시작한다 */
public enum SalesUnit {
    DAY, WEEK, MONTH;

    private static final DateTimeFormatter MONTH_LABEL = DateTimeFormatter.ofPattern("yyyy-MM");

    /** 비우면 day. day·week·month(대소문자 무시)가 아니면 400 VALIDATION_ERROR */
    public static SalesUnit of(String value) {
        if (value == null || value.isBlank()) {
            return DAY;
        }
        try {
            return valueOf(value.trim().toUpperCase(Locale.ROOT));
        } catch (IllegalArgumentException e) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "unit: day·week·month 중 하나여야 합니다");
        }
    }

    /** 그 날짜가 들어가는 묶음의 첫날 */
    LocalDate startOf(LocalDate date) {
        return switch (this) {
            case DAY -> date;
            case WEEK -> date.with(TemporalAdjusters.previousOrSame(DayOfWeek.MONDAY));
            case MONTH -> date.withDayOfMonth(1);
        };
    }

    /** 다음 묶음의 첫날 */
    LocalDate next(LocalDate start) {
        return switch (this) {
            case DAY -> start.plusDays(1);
            case WEEK -> start.plusWeeks(1);
            case MONTH -> start.plusMonths(1);
        };
    }

    /** 응답의 period 값: 일·주는 첫날 yyyy-MM-dd, 월은 yyyy-MM */
    String label(LocalDate start) {
        return this == MONTH ? start.format(MONTH_LABEL) : start.toString();
    }
}
