package com.freshlink.order.domain.pickup;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;
import java.util.List;

/** 점주 일괄 픽업 승인: 표와 고른 주문(1~20건) */
public record PickupApproveRequest(
        @NotBlank(message = "필수입니다") String token,
        @NotEmpty(message = "주문을 하나 이상 골라 주세요")
        @Size(max = PickupPassService.MAX_APPROVE, message = "한 번에 20건까지 승인할 수 있습니다")
        List<@NotNull(message = "필수입니다") Long> orderIds
) {
}
