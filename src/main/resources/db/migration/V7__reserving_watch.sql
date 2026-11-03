-- A12 재고 확인 결과 미수신 감지 (10/29). 감지·경고만 하고 주문 상태는 바꾸지 않는다
ALTER TABLE orders
    ADD COLUMN reserving_alerted_at DATETIME(6) NULL COMMENT 'A12 RESERVING이 기준 시간을 넘어 경고를 남긴 시각 (한 번만)' AFTER closed_at,
    ADD KEY idx_orders_status_created (status, created_at);
