package com.freshlink.order.common.web;

/** gateway가 넘기는 헤더 이름과 역할 값. 서비스는 토큰을 다시 검증하지 않는다 */
public final class AuthHeaders {

    public static final String USER_ID = "X-User-Id";
    public static final String USER_ROLE = "X-User-Role";
    public static final String STORE_ID = "X-Store-Id";
    public static final String IDEMPOTENCY_KEY = "Idempotency-Key";

    public static final String ROLE_CUSTOMER = "CUSTOMER";
    public static final String ROLE_OWNER = "OWNER";

    private AuthHeaders() {
    }
}
