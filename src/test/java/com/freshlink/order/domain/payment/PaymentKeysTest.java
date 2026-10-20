package com.freshlink.order.domain.payment;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

import static org.assertj.core.api.Assertions.assertThat;

class PaymentKeysTest {

    private static DataIntegrityViolationException violation(String constraint, String sqlMessage) {
        SQLIntegrityConstraintViolationException sql = new SQLIntegrityConstraintViolationException(sqlMessage);
        return new DataIntegrityViolationException("could not execute statement",
                new ConstraintViolationException("could not execute statement", sql, constraint));
    }

    @Test
    void paymentKey_UNIQUE_충돌만_알아본다() {
        assertThat(PaymentKeys.isDuplicate(violation("uk_payment_key",
                "Duplicate entry 'pk' for key 'payment.uk_payment_key'"))).isTrue();
        assertThat(PaymentKeys.isDuplicate(violation(null,
                "Duplicate entry 'pk' for key 'payment.uk_payment_key'"))).isTrue();
    }

    @Test
    void 다른_무결성_오류는_아니다() {
        assertThat(PaymentKeys.isDuplicate(violation("uk_payment_order",
                "Duplicate entry '1' for key 'payment.uk_payment_order'"))).isFalse();
        assertThat(PaymentKeys.isDuplicate(violation(null, "Column 'payment_key' cannot be null"))).isFalse();
        assertThat(PaymentKeys.isDuplicate(new DataIntegrityViolationException("x"))).isFalse();
    }
}
