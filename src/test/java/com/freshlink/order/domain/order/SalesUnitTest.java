package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import java.time.LocalDate;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesUnitTest {

    @Test
    void 비우면_day이고_대소문자는_무시한다() {
        assertThat(SalesUnit.of(null)).isEqualTo(SalesUnit.DAY);
        assertThat(SalesUnit.of("")).isEqualTo(SalesUnit.DAY);
        assertThat(SalesUnit.of("Week")).isEqualTo(SalesUnit.WEEK);
        assertThat(SalesUnit.of("month")).isEqualTo(SalesUnit.MONTH);
    }

    @Test
    void 모르는_단위는_400() {
        assertThatThrownBy(() -> SalesUnit.of("year"))
                .isInstanceOf(BusinessException.class)
                .hasMessageContaining("unit:");
    }

    @Test
    void 주는_월요일에_시작한다() {
        // 2026-10-29는 목요일
        assertThat(SalesUnit.WEEK.startOf(LocalDate.of(2026, 10, 29))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(SalesUnit.WEEK.startOf(LocalDate.of(2026, 10, 26))).isEqualTo(LocalDate.of(2026, 10, 26));
        assertThat(SalesUnit.WEEK.startOf(LocalDate.of(2026, 11, 1))).isEqualTo(LocalDate.of(2026, 10, 26));
    }

    @Test
    void 월_라벨은_yyyy_MM이고_일_주는_첫날() {
        assertThat(SalesUnit.MONTH.label(LocalDate.of(2026, 10, 1))).isEqualTo("2026-10");
        assertThat(SalesUnit.WEEK.label(LocalDate.of(2026, 10, 26))).isEqualTo("2026-10-26");
        assertThat(SalesUnit.DAY.label(LocalDate.of(2026, 10, 30))).isEqualTo("2026-10-30");
    }
}
