package com.freshlink.order.domain.payment;

import org.hibernate.exception.ConstraintViolationException;
import org.springframework.dao.DataIntegrityViolationException;

import java.sql.SQLIntegrityConstraintViolationException;

/** payment 표의 paymentKey UNIQUE 충돌(uk_payment_key)인지 가린다. 다른 무결성 오류는 해당하지 않는다 */
final class PaymentKeys {

    static final String UNIQUE_KEY = "uk_payment_key";

    private PaymentKeys() {
    }

    static boolean isDuplicate(DataIntegrityViolationException e) {
        for (Throwable t = e; t != null; t = t.getCause()) {
            if (t instanceof ConstraintViolationException c && c.getConstraintName() != null
                    && c.getConstraintName().contains(UNIQUE_KEY)) {
                return true;
            }
            if (t instanceof SQLIntegrityConstraintViolationException s && s.getMessage() != null
                    && s.getMessage().contains(UNIQUE_KEY)) {
                return true;
            }
        }
        return false;
    }
}
