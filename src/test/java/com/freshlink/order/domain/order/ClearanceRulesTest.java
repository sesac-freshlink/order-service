package com.freshlink.order.domain.order;

import java.time.Instant;
import java.time.OffsetDateTime;
import java.util.List;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

/** 마감세일 함께 사기 규칙: 판매 시간, 금액, 마감세일 lot 검사, 당일 픽업 기한 */
class ClearanceRulesTest {

    private static Instant kst(String time) {
        return OffsetDateTime.parse("2026-11-06T" + time + "+09:00").toInstant();
    }

    @Test
    void 판매_시간은_KST_06시_이상_23시_미만() {
        assertThat(ClearanceRules.onSale(kst("05:59:59"))).isFalse();
        assertThat(ClearanceRules.onSale(kst("06:00:00"))).isTrue();
        assertThat(ClearanceRules.onSale(kst("22:59:59"))).isTrue();
        assertThat(ClearanceRules.onSale(kst("23:00:00"))).isFalse();
        assertThat(ClearanceRules.onSale(kst("00:10:00"))).isFalse();
    }

    @Test
    void 금액은_lot_단가_없으면_기본_단가로_계산해_더한다() {
        List<ClearanceRules.PricedLot> lots = List.of(
                new ClearanceRules.PricedLot(1, true, 3490L),
                new ClearanceRules.PricedLot(2, false, null));
        assertThat(ClearanceRules.amount(4990L, lots)).isEqualTo(3490L + 4990L * 2);
    }

    @Test
    void 마감세일_lot_수량_합이_요청과_같고_단가가_있어야_맞다() {
        List<ClearanceRules.PricedLot> ok = List.of(
                new ClearanceRules.PricedLot(1, true, 3490L), new ClearanceRules.PricedLot(1, false, null));
        assertThat(ClearanceRules.matches(1, ok)).isTrue();
        // 마감세일을 요청했는데 마감세일 lot이 없다 (옛 inventory)
        assertThat(ClearanceRules.matches(1, List.of(new ClearanceRules.PricedLot(2, false, null)))).isFalse();
        // 반만 맞게 왔다 (요청 2, 마감세일 lot 1)
        assertThat(ClearanceRules.matches(2, ok)).isFalse();
        // 마감세일 lot인데 단가가 없다
        assertThat(ClearanceRules.matches(1, List.of(new ClearanceRules.PricedLot(1, true, null)))).isFalse();
        // 마감세일 없는 주문에 마감세일 lot이 왔다
        assertThat(ClearanceRules.matches(0, ok)).isFalse();
        // 마감세일 없는 주문은 기본 lot만
        assertThat(ClearanceRules.matches(0, List.of(new ClearanceRules.PricedLot(3, false, null)))).isTrue();
    }

    @Test
    void 마감세일이_섞이면_재고가_잡힌_날_23시59분59초와_가장_빠른_기한_중_이른_쪽() {
        OffsetDateTime reservedAt = OffsetDateTime.parse("2026-11-06T10:00:00+09:00");
        OffsetDateTime later = OffsetDateTime.parse("2026-11-09T23:59:59+09:00");
        OffsetDateTime earlier = OffsetDateTime.parse("2026-11-06T18:00:00+09:00");

        assertThat(ClearanceRules.pickupDeadline(later, reservedAt, false)).isEqualTo(later);
        assertThat(ClearanceRules.pickupDeadline(later, reservedAt, true).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-06T23:59:59+09:00").toInstant());
        assertThat(ClearanceRules.pickupDeadline(earlier, reservedAt, true)).isEqualTo(earlier);
    }

    @Test
    void 당일은_UTC가_아니라_KST_날짜로_본다() {
        // KST 11/7 08:00 = UTC 11/6 23:00
        OffsetDateTime reservedAt = OffsetDateTime.parse("2026-11-06T23:00:00Z");
        OffsetDateTime later = OffsetDateTime.parse("2026-11-09T23:59:59+09:00");
        assertThat(ClearanceRules.pickupDeadline(later, reservedAt, true).toInstant())
                .isEqualTo(OffsetDateTime.parse("2026-11-07T23:59:59+09:00").toInstant());
    }
}
