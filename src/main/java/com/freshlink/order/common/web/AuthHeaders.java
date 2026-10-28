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

    /** X-User-Role에 그 역할이 있는지 본다. 그룹이 여럿이면 gateway가 쉼표로 이어 보낸다 (예: "CUSTOMER,OWNER") */
    public static boolean hasRole(String roleHeader, String role) {
        if (roleHeader == null) {
            return false;
        }
        for (String r : roleHeader.split(",")) {
            if (r.trim().equals(role)) {
                return true;
            }
        }
        return false;
    }
}
