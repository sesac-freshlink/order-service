-- order-service V1: 주문·주문 lot·결제·ShedLock 표 (설계: member·order MVP ERD, 2026-10-15)
-- 스키마: orders. 물리 FK는 두지 않고 인덱스만 둔다. outbox·processed_message는 common v0.2.0 DDL을 V2로 넣는다.

CREATE TABLE orders (
    id                  BIGINT       NOT NULL AUTO_INCREMENT,
    order_no            VARCHAR(30)  NULL     COMMENT 'FL-yyyyMMdd-000123, INSERT 직후 같은 트랜잭션에서 채움. 토스 orderId',
    customer_id         VARCHAR(36)  NOT NULL COMMENT 'X-User-Id (Cognito sub) = member.member.id, 논리적 연결만',
    request_id          VARCHAR(36)  NOT NULL COMMENT '주문 생성 Idempotency-Key (UUID)',
    store_id            BIGINT       NOT NULL COMMENT 'X-Store-Id 아님, 요청 본문. 논리적 연결만 (member.store.id)',
    store_name          VARCHAR(30)  NOT NULL COMMENT '요청 본문 값, 표시용',
    product_id          BIGINT       NOT NULL,
    product_name        VARCHAR(100) NULL     COMMENT 'RESERVED.productName',
    quantity            INT          NOT NULL COMMENT '1~5',
    unit_price          BIGINT       NULL     COMMENT 'RESERVED.unitPrice (원)',
    amount              BIGINT       NULL     COMMENT 'unit_price × quantity',
    status              VARCHAR(30)  NOT NULL COMMENT 'RESERVING / PAYMENT_WAITING / PAYMENT_APPROVING / PAID / OUT_OF_STOCK / PAYMENT_FAILED / EXPIRED',
    failure_reason      VARCHAR(30)  NULL     COMMENT 'OUT_OF_STOCK / PRODUCT_NOT_FOUND / INVALID_QUANTITY / PAYMENT_FAILED / EXPIRED',
    available_quantity  INT          NULL     COMMENT 'RESERVE_FAILED.availableQuantity',
    payment_waiting_at  DATETIME(6)  NULL     COMMENT 'PAYMENT_WAITING 전환 시각',
    payment_expires_at  DATETIME(6)  NULL     COMMENT 'payment_waiting_at + 10분',
    paid_at             DATETIME(6)  NULL,
    closed_at           DATETIME(6)  NULL     COMMENT '종료 상태가 된 시각',
    created_at          DATETIME(6)  NOT NULL,
    updated_at          DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_orders_customer_request (customer_id, request_id),
    UNIQUE KEY uk_orders_order_no (order_no),
    KEY idx_orders_status_expires (status, payment_expires_at),
    KEY idx_orders_store_status_paid (store_id, status, paid_at)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE order_lot (
    id          BIGINT      NOT NULL AUTO_INCREMENT,
    order_id    BIGINT      NOT NULL,
    lot_id      BIGINT      NOT NULL COMMENT 'inventory lot ID (RESERVED.lots)',
    quantity    INT         NOT NULL,
    expiry_at   DATETIME(6) NOT NULL COMMENT 'RESERVED.lots[].expiryDate (OffsetDateTime, 예: 23:59:59+09:00) 그대로',
    created_at  DATETIME(6) NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_order_lot (order_id, lot_id)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

CREATE TABLE payment (
    id                    BIGINT       NOT NULL AUTO_INCREMENT,
    order_id              BIGINT       NOT NULL COMMENT '주문 1건당 승인 시도 1건',
    payment_key           VARCHAR(200) NOT NULL COMMENT '토스 paymentKey',
    toss_idempotency_key  VARCHAR(36)  NOT NULL COMMENT '토스 승인 Idempotency-Key (주문 생성 키와 다름)',
    amount                BIGINT       NOT NULL,
    status                VARCHAR(20)  NOT NULL COMMENT 'APPROVING / DONE / FAILED',
    toss_status           VARCHAR(30)  NULL     COMMENT '토스 응답·조회의 status (DONE, ABORTED …)',
    method                VARCHAR(30)  NULL     COMMENT '결제 수단',
    error_code            VARCHAR(80)  NULL     COMMENT '마지막 토스 오류 코드',
    error_message         VARCHAR(500) NULL,
    approved_at           DATETIME(6)  NULL     COMMENT '토스 approvedAt',
    created_at            DATETIME(6)  NOT NULL,
    updated_at            DATETIME(6)  NOT NULL,
    PRIMARY KEY (id),
    UNIQUE KEY uk_payment_order (order_id),
    UNIQUE KEY uk_payment_key (payment_key)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4 COLLATE=utf8mb4_0900_ai_ci;

-- ShedLock (만료 스케줄러). 파드 2개에서 한 곳만 실행
CREATE TABLE shedlock (
    name       VARCHAR(64)  NOT NULL,
    lock_until TIMESTAMP(3) NOT NULL,
    locked_at  TIMESTAMP(3) NOT NULL DEFAULT CURRENT_TIMESTAMP(3),
    locked_by  VARCHAR(255) NOT NULL,
    PRIMARY KEY (name)
) ENGINE=InnoDB DEFAULT CHARSET=utf8mb4;
