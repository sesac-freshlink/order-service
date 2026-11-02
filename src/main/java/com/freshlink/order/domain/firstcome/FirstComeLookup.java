package com.freshlink.order.domain.firstcome;

/**
 * inventory 남은 수량 API 결과.
 * FIRST_COME = 선착순 상품(limit·remaining 있음), NOT_FIRST_COME = 404(선착순이 아님), UNKNOWN = 타임아웃·오류(알 수 없음)
 */
public record FirstComeLookup(Kind kind, Integer limit, Integer remaining) {

    public enum Kind { FIRST_COME, NOT_FIRST_COME, UNKNOWN }

    public static FirstComeLookup firstCome(int limit, int remaining) {
        return new FirstComeLookup(Kind.FIRST_COME, limit, remaining);
    }

    public static FirstComeLookup notFirstCome() {
        return new FirstComeLookup(Kind.NOT_FIRST_COME, null, null);
    }

    public static FirstComeLookup unknown() {
        return new FirstComeLookup(Kind.UNKNOWN, null, null);
    }

    public boolean isFirstCome() {
        return kind == Kind.FIRST_COME;
    }
}
