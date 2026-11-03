-- 토스 결제 내역 연동: 승인 응답 receipt.url(영수증 페이지)을 남긴다. 이전 결제는 NULL
ALTER TABLE payment
    ADD COLUMN receipt_url VARCHAR(500) NULL COMMENT '토스 receipt.url (영수증 페이지)' AFTER approved_at;
