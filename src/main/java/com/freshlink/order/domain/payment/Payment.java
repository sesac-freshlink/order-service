package com.freshlink.order.domain.payment;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.Duration;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 주문 1건당 승인 시도 1건 (표 payment, V1). 토스 오류 코드·메시지는 여기와 로그에만 남긴다 */
@Entity
@Table(name = "payment")
@EntityListeners(AuditingEntityListener.class)
public class Payment {

    private static final int ERROR_CODE_MAX = 80;
    private static final int ERROR_MESSAGE_MAX = 500;
    private static final int SHORT_MAX = 30;
    private static final int RECEIPT_URL_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false, unique = true)
    private Long orderId;

    @Column(name = "payment_key", nullable = false, length = 200, unique = true)
    private String paymentKey;

    @Column(name = "toss_idempotency_key", nullable = false, length = 36)
    private String tossIdempotencyKey;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private PaymentStatus status;

    @Column(name = "toss_status", length = 30)
    private String tossStatus;

    @Column(name = "method", length = 30)
    private String method;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "approved_at")
    private OffsetDateTime approvedAt;

    @Column(name = "receipt_url", length = RECEIPT_URL_MAX)
    private String receiptUrl;

    /** A11 자동 조회 횟수 (V6) */
    @Column(name = "sync_attempts", nullable = false)
    private int syncAttempts;

    /** A11 다음 자동 조회 시각. null이면 자동 조회 대상이 아니다 (결과 확정 또는 한도 초과) (V6) */
    @Column(name = "next_sync_at")
    private OffsetDateTime nextSyncAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Payment() {
    }

    /** 승인 시작. 토스 멱등키는 주문 생성 키와 다른 새 UUID다 */
    public static Payment start(Long orderId, String paymentKey, String tossIdempotencyKey, long amount) {
        Payment p = new Payment();
        p.orderId = orderId;
        p.paymentKey = paymentKey;
        p.tossIdempotencyKey = tossIdempotencyKey;
        p.amount = amount;
        p.status = PaymentStatus.APPROVING;
        return p;
    }

    /** 승인 성공. 영수증 주소는 500자를 넘으면 잘린 주소가 되므로 남기지 않는다 */
    public void approve(String tossStatus, String method, OffsetDateTime approvedAt, String receiptUrl) {
        this.status = PaymentStatus.DONE;
        this.tossStatus = cut(tossStatus, SHORT_MAX);
        this.method = cut(method, SHORT_MAX);
        this.approvedAt = approvedAt;
        this.receiptUrl = receiptUrl == null || receiptUrl.length() > RECEIPT_URL_MAX ? null : receiptUrl;
    }

    public void fail(String errorCode, String errorMessage) {
        this.status = PaymentStatus.FAILED;
        recordError(errorCode, errorMessage);
    }

    /** 결과 불명·연동 오류: 상태는 APPROVING 그대로 두고 마지막 오류만 남긴다 */
    public void recordError(String errorCode, String errorMessage) {
        this.errorCode = cut(errorCode, ERROR_CODE_MAX);
        this.errorMessage = cut(errorMessage, ERROR_MESSAGE_MAX);
    }

    /** A11 결과 불명: 곧바로 첫 자동 조회를 하도록 예약한다 */
    public void scheduleSync(OffsetDateTime now) {
        this.syncAttempts = 0;
        this.nextSyncAt = now;
    }

    /**
     * A11 자동 조회 한 번을 마쳤는데 아직 결과 불명이다. 횟수를 올리고 interval 뒤로 다시 예약한다.
     * maxAttempts에 닿으면 예약을 지우고 true (수동 확인 대상)
     */
    public boolean afterSyncAttempt(OffsetDateTime now, Duration interval, int maxAttempts) {
        this.syncAttempts++;
        if (syncAttempts >= maxAttempts) {
            this.nextSyncAt = null;
            return true;
        }
        this.nextSyncAt = now.plus(interval);
        return false;
    }

    /** A11 결과가 확정됐거나 더 볼 필요가 없다 */
    public void stopSync() {
        this.nextSyncAt = null;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public String getPaymentKey() { return paymentKey; }
    public String getTossIdempotencyKey() { return tossIdempotencyKey; }
    public long getAmount() { return amount; }
    public PaymentStatus getStatus() { return status; }
    public String getTossStatus() { return tossStatus; }
    public String getMethod() { return method; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getApprovedAt() { return approvedAt; }
    public String getReceiptUrl() { return receiptUrl; }
    public int getSyncAttempts() { return syncAttempts; }
    public OffsetDateTime getNextSyncAt() { return nextSyncAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
