package com.freshlink.order.domain.order;

import com.freshlink.common.error.BusinessException;
import com.freshlink.order.common.error.OrderErrorCode;
import java.util.Set;

/** A3·A6 목록 페이지 값. page는 0부터, size는 10·20·50만 (추가 명세 A3·A6, 기본 20). 어기면 400 VALIDATION_ERROR */
public record OrderPageRequest(int page, int size) {

    public static final int DEFAULT_SIZE = 20;
    private static final Set<Integer> SIZES = Set.of(10, 20, 50);

    public static OrderPageRequest of(Integer page, Integer size) {
        int p = page == null ? 0 : page;
        int s = size == null ? DEFAULT_SIZE : size;
        if (p < 0) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "page: 0 이상이어야 합니다");
        }
        if (!SIZES.contains(s)) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "size: 10, 20, 50 중 하나여야 합니다");
        }
        return new OrderPageRequest(p, s);
    }

    /** 구분 이름을 enum으로. 모르는 값이면 400 */
    public static <E extends Enum<E>> E tab(Class<E> type, String value) {
        try {
            return Enum.valueOf(type, value);
        } catch (IllegalArgumentException | NullPointerException e) {
            throw new BusinessException(OrderErrorCode.VALIDATION_ERROR, "tab: 알 수 없는 구분입니다");
        }
    }
}
