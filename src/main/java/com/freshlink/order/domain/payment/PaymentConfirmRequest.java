package com.freshlink.order.domain.payment;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

/** OR-03 요청. 토스 성공 URL로 받은 값 그대로 */
public record PaymentConfirmRequest(
        @NotBlank @Size(max = 200) String paymentKey,
        @NotBlank @Size(max = 64) String orderNo,
        @NotNull @Positive Long amount
) {
}
