package com.freshlink.order.domain.firstcome;

import java.time.OffsetDateTime;

/**
 * 줄 서기·내 순번 응답.
 *
 * @param position        1부터 센 내 순번 (WAITING일 때만)
 * @param ahead           내 앞에 남은 사람 수 (WAITING일 때만)
 * @param waiting         지금 줄에 선 사람 수
 * @param remaining       지금 더 살 수 있는 수량 (inventory 남은 수량). 알 수 없으면 null
 * @param ticketExpiresAt 입장권 만료 시각 (ADMITTED일 때만). 이때까지 [주문하기]를 눌러야 한다
 * @param serverTime      서버 현재 시각 (화면이 남은 시간을 셀 때 쓴다)
 */
public record QueueStatusResponse(
        Long storeId,
        Long productId,
        QueueStatus status,
        Long position,
        Long ahead,
        long waiting,
        Integer remaining,
        OffsetDateTime ticketExpiresAt,
        OffsetDateTime serverTime
) {
}
