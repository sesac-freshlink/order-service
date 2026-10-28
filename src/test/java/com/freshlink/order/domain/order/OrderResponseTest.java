package com.freshlink.order.domain.order;

import com.freshlink.order.domain.payment.Payment;
import com.freshlink.order.domain.payment.PaymentStatus;
import java.time.OffsetDateTime;
import java.time.ZoneOffset;
import org.junit.jupiter.api.Test;
import org.springframework.test.util.ReflectionTestUtils;

import static org.assertj.core.api.Assertions.assertThat;

/** 응답 시각은 Jackson 설정과 상관없이 DTO에서 +09:00으로 바뀐다 */
class OrderResponseTest {

    @Test
    void UTC로_읽힌_시각도_KST로_바꾼다() {
        Order order = Order.create("c", "k", 1L, "점포", 10L, 2);
        ReflectionTestUtils.setField(order, "createdAt", OffsetDateTime.parse("2026-10-20T06:31:55Z"));
        order.reserve("상품", 1000L, OffsetDateTime.parse("2026-10-20T06:32:00Z"));

        OrderResponse res = OrderResponse.from(order, null, OffsetDateTime.parse("2026-10-20T06:35:00Z"));

        assertThat(res.createdAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T15:31:55+09:00"));
        assertThat(res.createdAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(res.paymentExpiresAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(res.paidAt()).isNull();
        assertThat(res.payment()).isNull();
        assertThat(res.serverTime()).isEqualTo(OffsetDateTime.parse("2026-10-20T15:35:00+09:00"));
        assertThat(res.serverTime().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
    }

    @Test
    void 결제_내역은_승인_시각을_KST로_바꾸고_영수증_주소를_그대로_내린다() {
        Payment payment = Payment.start(1L, "pk_1", "33333333-3333-3333-3333-333333333333", 3400L);
        payment.approve("DONE", "카드", OffsetDateTime.parse("2026-10-20T06:35:10Z"), "https://receipt.test/1");

        OrderPaymentResponse res = OrderPaymentResponse.from(payment);

        assertThat(res.method()).isEqualTo("카드");
        assertThat(res.approvedAt()).isEqualTo(OffsetDateTime.parse("2026-10-20T15:35:10+09:00"));
        assertThat(res.approvedAt().getOffset()).isEqualTo(ZoneOffset.ofHours(9));
        assertThat(res.status()).isEqualTo(PaymentStatus.DONE);
        assertThat(res.receiptUrl()).isEqualTo("https://receipt.test/1");
    }

    @Test
    void 승인_중인_결제는_상태만_있고_승인_시각과_영수증은_null() {
        Payment payment = Payment.start(1L, "pk_1", "33333333-3333-3333-3333-333333333333", 3400L);

        OrderPaymentResponse res = OrderPaymentResponse.from(payment);

        assertThat(res.status()).isEqualTo(PaymentStatus.APPROVING);
        assertThat(res.approvedAt()).isNull();
        assertThat(res.receiptUrl()).isNull();
        assertThat(OrderPaymentResponse.from(null)).isNull();
    }

    @Test
    void 영수증_주소가_500자를_넘으면_잘린_주소를_남기지_않는다() {
        Payment payment = Payment.start(1L, "pk_1", "33333333-3333-3333-3333-333333333333", 3400L);
        payment.approve("DONE", "카드", OffsetDateTime.parse("2026-10-20T06:35:10Z"), "https://r.test/" + "a".repeat(500));

        assertThat(payment.getReceiptUrl()).isNull();
        assertThat(payment.getStatus()).isEqualTo(PaymentStatus.DONE);
    }
}
