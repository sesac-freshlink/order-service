-- 결제 대기 주문 겹침 막기 (11/15 김경우 결정 C).
-- 같은 고객·점포·상품의 열린 주문(RESERVING·PAYMENT_WAITING)은 한 건만 둔다. 승인 중(PAYMENT_APPROVING)은 이미 결제하는 중이라 넣지 않는다.
-- open_key는 열린 동안만 '고객:점포:상품'이고, 상태가 바뀌면(결제·만료·취소·재고 없음) NULL이 되어 다시 살 수 있다. NULL은 UNIQUE에 걸리지 않는다.
-- 배포 전: 같은 open_key가 두 건 이상인 열린 주문이 없어야 색인이 만들어진다.
ALTER TABLE orders
    ADD COLUMN open_key VARCHAR(100)
        GENERATED ALWAYS AS (
            CASE WHEN status IN ('RESERVING', 'PAYMENT_WAITING')
                 THEN CONCAT(customer_id, ':', store_id, ':', product_id) END
        ) STORED COMMENT '열린 주문 표시 (고객:점포:상품, 아니면 NULL)',
    ADD UNIQUE KEY uk_orders_open (open_key);
