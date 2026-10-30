package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import java.time.OffsetDateTime;
import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SalesPeriodTest {

    @Test
    void KST_날짜_양_끝을_포함한다() {
        SalesPeriod p = SalesPeriod.of("2026-10-20", "2026-10-21");

        assertThat(p.start()).isEqualTo(OffsetDateTime.parse("2026-10-20T00:00:00+09:00"));
        assertThat(p.end()).isEqualTo(OffsetDateTime.parse("2026-10-22T00:00:00+09:00"));
    }

    @Test
    void from_to는_필수다() {
        assertThatThrownBy(() -> SalesPeriod.of(null, "2026-10-21")).hasMessageContaining("from:");
        assertThatThrownBy(() -> SalesPeriod.of("2026-10-20", " ")).hasMessageContaining("to:");
    }

    @Test
    void 형식이_틀리거나_from이_늦으면_400() {
        assertThatThrownBy(() -> SalesPeriod.of("2026/10/20", "2026-10-21"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("yyyy-MM-dd");
        assertThatThrownBy(() -> SalesPeriod.of("2026-10-22", "2026-10-21"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("from:");
    }

    @Test
    void 기간은_366일까지() {
        assertThat(SalesPeriod.of("2026-01-01", "2027-01-01").to().toString()).isEqualTo("2027-01-01");
        assertThatThrownBy(() -> SalesPeriod.of("2026-01-01", "2027-01-02"))
                .isInstanceOf(BusinessException.class).hasMessageContaining("366");
    }
}
