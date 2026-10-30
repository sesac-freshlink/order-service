package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeParseException;
import java.time.temporal.ChronoUnit;

/**
 * 매출 조회 기간. KST 날짜 yyyy-MM-dd, from·to 둘 다 포함하고 둘 다 필수다.
 * 한 번에 최대 366일까지 본다 (묶음 수가 끝없이 늘지 않게)
 */
public record SalesPeriod(LocalDate from, LocalDate to) {

    static final ZoneId KST = ZoneId.of("Asia/Seoul");
    static final long MAX_DAYS = 366;

    public static SalesPeriod of(String from, String to) {
        LocalDate f = date("from", from);
        LocalDate t = date("to", to);
        if (f.isAfter(t)) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "from: to보다 늦을 수 없습니다");
        }
        if (ChronoUnit.DAYS.between(f, t) + 1 > MAX_DAYS) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "to: 기간은 최대 " + MAX_DAYS + "일입니다");
        }
        return new SalesPeriod(f, t);
    }

    /** from 00:00 KST 이상 */
    OffsetDateTime start() {
        return from.atStartOfDay(KST).toOffsetDateTime();
    }

    /** to 다음 날 00:00 KST 미만 */
    OffsetDateTime end() {
        return to.plusDays(1).atStartOfDay(KST).toOffsetDateTime();
    }

    private static LocalDate date(String name, String value) {
        if (value == null || value.isBlank()) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, name + ": 필수입니다");
        }
        try {
            return LocalDate.parse(value.trim());
        } catch (DateTimeParseException e) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, name + ": yyyy-MM-dd 형식이어야 합니다");
        }
    }
}
