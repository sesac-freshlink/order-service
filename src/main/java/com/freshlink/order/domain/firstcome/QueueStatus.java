package com.freshlink.order.domain.firstcome;

/**
 * 대기 화면이 보는 내 상태.
 * WAITING = 줄에서 기다림, ADMITTED = 입장권 있음(주문 화면으로), SOLD_OUT = 남은 수량 0이고 입장권도 없음,
 * NOT_IN_QUEUE = 줄에 없음(그만 기다렸거나 순번 조회가 30초 끊겨 빠짐)
 */
public enum QueueStatus {
    WAITING,
    ADMITTED,
    SOLD_OUT,
    NOT_IN_QUEUE
}
