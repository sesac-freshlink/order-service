-- A11 결제 결과 자동 조회 (10/29). 결과 불명이면 곧바로 첫 조회, 이후 30초마다, 모두 11번(첫 1 + 추가 10)까지
ALTER TABLE payment
    ADD COLUMN sync_attempts INT         NOT NULL DEFAULT 0 COMMENT 'A11 자동 조회 횟수' AFTER receipt_url,
    ADD COLUMN next_sync_at  DATETIME(6) NULL     COMMENT 'A11 다음 자동 조회 시각. NULL이면 자동 조회 안 함(확정 또는 한도 초과 → OP-01 수동 확인)' AFTER sync_attempts,
    ADD KEY idx_payment_status_next_sync (status, next_sync_at);
