package com.freshlink.order.domain.order;

import java.util.List;
import java.util.function.Function;
import org.springframework.data.domain.Page;

/** 목록 한 페이지 (member-service 관리자 목록과 같은 모양). page는 0부터 */
public record PageResponse<T>(List<T> content, int page, int size, long totalElements, int totalPages) {

    public static <E, T> PageResponse<T> of(Page<E> page, Function<E, T> mapper) {
        return new PageResponse<>(page.getContent().stream().map(mapper).toList(), page.getNumber(), page.getSize(),
                page.getTotalElements(), page.getTotalPages());
    }

    public static <T> PageResponse<T> empty(int page, int size) {
        return new PageResponse<>(List.of(), page, size, 0, 0);
    }
}
