-- A4·A5 픽업 기한·픽업 완료 (10/29). 상태 PICKED_UP·PICKUP_EXPIRED, 실패 사유 PICKUP_DEADLINE이 더해진다
ALTER TABLE orders
    ADD COLUMN pickup_deadline DATETIME(6) NULL COMMENT 'RESERVED lot 유통기한 중 가장 빠른 값 (그날 23:59:59 KST)' AFTER paid_at,
    ADD COLUMN picked_up_at    DATETIME(6) NULL COMMENT '점주 픽업 확인 시각 (PICKED_UP)' AFTER pickup_deadline,
    MODIFY COLUMN status         VARCHAR(30) NOT NULL COMMENT 'RESERVING / PAYMENT_WAITING / PAYMENT_APPROVING / PAID / OUT_OF_STOCK / PAYMENT_FAILED / EXPIRED / PICKED_UP / PICKUP_EXPIRED',
    MODIFY COLUMN failure_reason VARCHAR(30) NULL     COMMENT 'OUT_OF_STOCK / PRODUCT_NOT_FOUND / INVALID_QUANTITY / PAYMENT_FAILED / EXPIRED / PICKUP_DEADLINE',
    ADD KEY idx_orders_status_pickup (status, pickup_deadline);

-- 이미 재고를 확보한 주문은 저장된 lot에서 기한을 채운다
UPDATE orders o
    JOIN (SELECT order_id, MIN(expiry_at) AS earliest FROM order_lot GROUP BY order_id) l ON l.order_id = o.id
SET o.pickup_deadline = l.earliest
WHERE o.pickup_deadline IS NULL;
