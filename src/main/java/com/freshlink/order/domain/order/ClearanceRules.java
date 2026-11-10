package com.freshlink.order.domain.order;

import java.time.Instant;
import java.time.LocalTime;
import java.time.OffsetDateTime;
import java.time.ZoneId;
import java.util.List;

/**
 * 마감세일 함께 사기 규칙 (3차 할 일 김경우 "마감세일 함께 사기", 11/6 경우·이태우 합의).
 * 판매 시간, 금액 계산, 마감세일 lot 검사, 당일 픽업 기한을 한곳에 둔다
 */
public final class ClearanceRules {

    private static final ZoneId KST = ZoneId.of("Asia/Seoul");
    /** 마감세일 판매 시간: KST 06:00 이상 23:00 미만 */
    static final LocalTime SALE_OPEN = LocalTime.of(6, 0);
    static final LocalTime SALE_CLOSE = LocalTime.of(23, 0);

    private ClearanceRules() {
    }

    /** RESERVED lot 한 줄. unitPrice가 없으면 주문의 기본 단가로 계산한다 */
    public record PricedLot(int quantity, boolean clearance, Long unitPrice) {
    }

    /** 지금이 마감세일 판매 시간(KST 06:00~23:00)인지 */
    public static boolean onSale(Instant now) {
        LocalTime time = now.atZone(KST).toLocalTime();
        return !time.isBefore(SALE_OPEN) && time.isBefore(SALE_CLOSE);
    }

    /** 주문 금액 = Σ (lot 단가, 없으면 기본 단가) × lot 수량 */
    public static long amount(long baseUnitPrice, List<PricedLot> lots) {
        return lots.stream()
                .mapToLong(lot -> (lot.unitPrice() != null ? lot.unitPrice() : baseUnitPrice) * lot.quantity())
                .sum();
    }

    /**
     * 받은 lot이 요청한 마감세일 수량과 맞는지: 마감세일 lot 수량 합 = 요청 마감세일 수량, 마감세일 lot은 단가가 있다.
     * 안 맞으면(옛 inventory, inventory 실수) 주문을 닫는다
     */
    public static boolean matches(int clearanceQuantity, List<PricedLot> lots) {
        int clearanceSum = lots.stream().filter(PricedLot::clearance).mapToInt(PricedLot::quantity).sum();
        boolean pricesPresent = lots.stream().filter(PricedLot::clearance).allMatch(lot -> lot.unitPrice() != null);
        return clearanceSum == clearanceQuantity && pricesPresent;
    }

    /**
     * 픽업 기한: 가장 빠른 lot 유통기한. 마감세일이 섞인 주문은 당일 픽업이라 재고가 잡힌 날(KST) 23:59:59와 견줘 이른 쪽
     */
    public static OffsetDateTime pickupDeadline(OffsetDateTime earliestExpiry, OffsetDateTime reservedAt,
                                                boolean hasClearance) {
        if (!hasClearance) {
            return earliestExpiry;
        }
        OffsetDateTime endOfDay = endOfDay(reservedAt);
        return endOfDay.isBefore(earliestExpiry) ? endOfDay : earliestExpiry;
    }

    /** 그날(KST) 23:59:59. 당일 픽업(마감세일)과 픽업 신청 기한이 같이 쓴다 */
    public static OffsetDateTime endOfDay(OffsetDateTime at) {
        return at.atZoneSameInstant(KST).toLocalDate().atTime(LocalTime.of(23, 59, 59)).atZone(KST).toOffsetDateTime();
    }
}
