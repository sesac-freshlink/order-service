-- A3 고객 주문 내역 (10/29): 본인 주문을 주문일시 최신순으로 읽는다 (customer_id = ? AND status IN (...) ORDER BY created_at DESC)
ALTER TABLE orders
    ADD KEY idx_orders_customer_created (customer_id, created_at);
