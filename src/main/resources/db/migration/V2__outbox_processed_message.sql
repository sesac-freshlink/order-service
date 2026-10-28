CREATE TABLE outbox (
    id           BIGINT AUTO_INCREMENT PRIMARY KEY,
    message_id   VARCHAR(36)  NOT NULL,          -- 쪽지 messageId. FIFO 중복 제거 ID로도 쓴다
    message_type VARCHAR(30)  NOT NULL,          -- RESERVE, RESERVED … (로그·조회용)
    queue_name   VARCHAR(200) NOT NULL,          -- 실제 큐 이름. 예: dev-inventory-commands.fifo
    group_id     VARCHAR(64)  NULL,              -- FIFO면 storeId 문자열, 표준 큐면 NULL
    payload      TEXT         NOT NULL,          -- MessageJson.toJson(쪽지)
    trace_parent VARCHAR(55)  NULL,              -- v0.3.0에서 채운다. v0.2.0은 항상 NULL
    published    BOOLEAN      NOT NULL DEFAULT FALSE,
    created_at   DATETIME(6)  NOT NULL,
    published_at DATETIME(6)  NULL,
    UNIQUE KEY uk_outbox_message_id (message_id),
    KEY idx_outbox_unpublished (published, id)
);

CREATE TABLE processed_message (
    message_id   VARCHAR(36) PRIMARY KEY,
    processed_at DATETIME(6) NOT NULL
);
