-- 마감세일 함께 사기 (11/6, 3차 할 일 김경우 "마감세일 함께 사기", 이태우 D안).
-- 주문 하나에 마감세일분과 기본분을 같이 담는다. quantity는 지금처럼 전체 수량이다
ALTER TABLE orders
    ADD COLUMN clearance_quantity           INT NOT NULL DEFAULT 0 COMMENT '전체 수량 중 마감세일 수량 (없으면 0)' AFTER quantity,
    ADD COLUMN clearance_available_quantity INT NULL COMMENT '재고 확보 실패 때 마감세일 남은 수 (RESERVE_FAILED.clearanceAvailableQuantity)' AFTER available_quantity;

-- lot마다 마감세일로 팔았는지와 그때 단가를 남긴다. 옛 주문은 기본(false)·단가 없음(주문 unit_price로 계산)
ALTER TABLE order_lot
    ADD COLUMN clearance  BOOLEAN NOT NULL DEFAULT FALSE COMMENT '마감세일 lot인지 (RESERVED.lots[].clearance)' AFTER quantity,
    ADD COLUMN unit_price BIGINT  NULL COMMENT '이 lot 단가. 없으면 주문 unit_price (RESERVED.lots[].unitPrice)' AFTER clearance;
