package com.freshlink.order.domain.refund;

import java.util.EnumSet;
import java.util.Map;
import java.util.Set;
import org.junit.jupiter.api.Test;

import static com.freshlink.order.domain.refund.RefundStatus.*;
import static org.assertj.core.api.Assertions.assertThat;

/** 허용되는 환불 상태 전이 표 (추가 명세 0.3 둘째 표) */
class RefundStatusTest {

    private static final Map<RefundStatus, Set<RefundStatus>> EXPECTED = Map.of(
            NONE, EnumSet.of(REQUESTED),
            FAILED, EnumSet.of(REQUESTED),
            REQUESTED, EnumSet.of(COMPLETED, FAILED, CHECKING),
            CHECKING, EnumSet.of(COMPLETED, FAILED));

    @Test
    void 명세_표에_있는_전이만_허용한다() {
        for (RefundStatus from : RefundStatus.values()) {
            Set<RefundStatus> allowed = EXPECTED.getOrDefault(from, EnumSet.noneOf(RefundStatus.class));
            for (RefundStatus to : RefundStatus.values()) {
                assertThat(from.canChangeTo(to)).as("%s -> %s", from, to).isEqualTo(allowed.contains(to));
            }
        }
    }

    @Test
    void 처리_중과_확인_중만_진행_중이다() {
        assertThat(EnumSet.allOf(RefundStatus.class)).filteredOn(RefundStatus::inProgress)
                .containsExactlyInAnyOrder(REQUESTED, CHECKING);
    }

    @Test
    void 사유는_요청자별로_고를_수_있고_기타는_둘_다_상세가_필수다() {
        assertThat(CancelReason.CHANGE_OF_MIND.allowedFor(CancelRequester.OWNER)).isFalse();
        assertThat(CancelReason.OUT_OF_STOCK.allowedFor(CancelRequester.CUSTOMER)).isFalse();
        assertThat(CancelReason.ETC.allowedFor(CancelRequester.CUSTOMER)).isTrue();
        assertThat(CancelReason.ETC.allowedFor(CancelRequester.OWNER)).isTrue();
        assertThat(EnumSet.allOf(CancelReason.class)).filteredOn(CancelReason::needsDetail)
                .containsExactly(CancelReason.ETC);
    }
}
