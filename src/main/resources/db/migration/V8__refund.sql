-- A8~A10 결제 후 취소·환불·재고 복원 (10/30). 주문 상태 REFUNDED와 환불 상태(추가 명세 0.2)가 더해진다.
-- 환불이 끝나기 전까지 주문은 PAID를 유지하고 refund_status로 픽업·추가 요청을 막는다
ALTER TABLE orders
    ADD COLUMN refund_status       VARCHAR(20) NOT NULL DEFAULT 'NONE' COMMENT 'NONE / REQUESTED / CHECKING / FAILED / COMPLETED' AFTER picked_up_at,
    ADD COLUMN cancel_requested_at DATETIME(6) NULL COMMENT '마지막 취소 요청 시각 (A6 취소·환불 구분 정렬)' AFTER refund_status,
    ADD COLUMN refunded_at         DATETIME(6) NULL COMMENT '환불 완료 시각 (토스 canceledAt)' AFTER cancel_requested_at,
    MODIFY COLUMN status VARCHAR(30) NOT NULL COMMENT 'RESERVING / PAYMENT_WAITING / PAYMENT_APPROVING / PAID / OUT_OF_STOCK / PAYMENT_FAILED / EXPIRED / PICKED_UP / PICKUP_EXPIRED / REFUNDED',
    ADD KEY idx_orders_store_cancel_requested (store_id, cancel_requested_at);

-- 환불 시도마다 한 줄. 재신청·재시도는 새 시도(새 토스 멱등키), 결과 불명인 같은 시도는 새로 실행하지 않고 조회로 확인한다
CREATE TABLE refund (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    order_id              BIGINT       NOT NULL,
    attempt_no            INT          NOT NULL COMMENT '같은 주문의 몇 번째 시도 (1부터)',
    requested_by          VARCHAR(10)  NOT NULL COMMENT 'CUSTOMER / OWNER',
    requester_id          VARCHAR(36)  NULL     COMMENT '요청한 사람의 X-User-Id',
    reason                VARCHAR(30)  NOT NULL COMMENT '취소 사유 (추가 명세 A8)',
    reason_detail         VARCHAR(200) NULL     COMMENT '기타 사유 상세 (토스 cancelReason 최대 200자)',
    amount                BIGINT       NOT NULL COMMENT '환불 요청 금액 (전액)',
    toss_idempotency_key  VARCHAR(36)  NOT NULL COMMENT '토스 취소 Idempotency-Key (시도마다 새 UUID)',
    status                VARCHAR(20)  NOT NULL COMMENT 'REQUESTED / CHECKING / FAILED / COMPLETED',
    requested_at          DATETIME(6)  NOT NULL COMMENT '취소 요청 시각',
    error_code            VARCHAR(80)  NULL     COMMENT '마지막 토스 오류 코드',
    error_message         VARCHAR(500) NULL,
    canceled_at           DATETIME(6)  NULL     COMMENT '토스 cancels[].canceledAt',
    sync_attempts         INT          NOT NULL DEFAULT 0 COMMENT '결과 확인 조회 횟수',
    next_sync_at          DATETIME(6)  NULL     COMMENT '다음 결과 확인 조회 시각. NULL이면 조회 안 함',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_refund_order_attempt (order_id, attempt_no),
    KEY idx_refund_status_next_sync (status, next_sync_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;
