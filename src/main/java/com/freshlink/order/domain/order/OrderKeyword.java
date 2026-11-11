package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import java.util.Locale;

/**
 * A3 고객 주문 내역 상품명 검색어 q (11/11). 앞뒤 공백을 빼고 50자까지 받는다. 비우면 조건이 없다.
 * 상품명 일부가 들어 있으면 맞는 주문이다(대소문자 구분 없음). LIKE 특수문자(% _)와 이스케이프 문자(!)는
 * 글자 그대로 찾도록 앞에 ! 를 붙인다. 50자를 넘으면 400 VALIDATION_ERROR
 */
public final class OrderKeyword {

    /** 상품명 검색어 최대 길이 (점포 상품 검색 U-10과 같은 50자) */
    public static final int MAX_LENGTH = 50;
    /** JPQL LIKE ... ESCAPE 에 쓰는 문자. MySQL에서 \ 는 문자열 안 이스케이프와 겹쳐 쓰지 않는다 */
    public static final char ESCAPE = '!';

    private OrderKeyword() {
    }

    /** 쿼리에 넘길 LIKE 패턴(%검색어%, 소문자). 검색어가 없으면 null */
    public static String likePattern(String q) {
        if (q == null) {
            return null;
        }
        String trimmed = q.strip();
        if (trimmed.isEmpty()) {
            return null;
        }
        if (trimmed.length() > MAX_LENGTH) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "q: " + MAX_LENGTH + "자 이하여야 합니다");
        }
        String escaped = trimmed.replace("!", "!!").replace("%", "!%").replace("_", "!_");
        return "%" + escaped.toLowerCase(Locale.ROOT) + "%";
    }
}
