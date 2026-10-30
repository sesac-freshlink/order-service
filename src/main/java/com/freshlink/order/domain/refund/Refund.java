package com.freshlink.order.domain.refund;

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

/**
 * 환불 시도 한 건 (표 refund, V8). 재신청·재시도마다 새 줄(새 토스 멱등키)을 만든다.
 * 결과 불명인 시도는 새로 실행하지 않고 조회로 결과를 확인한다(추가 명세 A9 "재시도마다 처리 시도를 구분")
 */
@Entity
@Table(name = "refund")
@EntityListeners(AuditingEntityListener.class)
public class Refund {

    private static final int ERROR_CODE_MAX = 80;
    private static final int ERROR_MESSAGE_MAX = 500;

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "attempt_no", nullable = false)
    private int attemptNo;

    @Enumerated(EnumType.STRING)
    @Column(name = "requested_by", nullable = false, length = 10)
    private CancelRequester requestedBy;

    @Column(name = "requester_id", length = 36)
    private String requesterId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason", nullable = false, length = 30)
    private CancelReason reason;

    @Column(name = "reason_detail", length = 200)
    private String reasonDetail;

    @Column(name = "amount", nullable = false)
    private long amount;

    @Column(name = "toss_idempotency_key", nullable = false, length = 36)
    private String tossIdempotencyKey;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "requested_at", nullable = false)
    private OffsetDateTime requestedAt;

    @Column(name = "error_code", length = 80)
    private String errorCode;

    @Column(name = "error_message", length = 500)
    private String errorMessage;

    @Column(name = "canceled_at")
    private OffsetDateTime canceledAt;

    @Column(name = "sync_attempts", nullable = false)
    private int syncAttempts;

    @Column(name = "next_sync_at")
    private OffsetDateTime nextSyncAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Refund() {
    }

    /** 새 시도. 토스 멱등키는 시도마다 새 UUID다 */
    public static Refund start(Long orderId, int attemptNo, CancelRequester requestedBy, String requesterId,
                               CancelReason reason, String reasonDetail, long amount, String tossIdempotencyKey,
                               OffsetDateTime requestedAt) {
        Refund r = new Refund();
        r.orderId = orderId;
        r.attemptNo = attemptNo;
        r.requestedBy = requestedBy;
        r.requesterId = requesterId;
        r.reason = reason;
        r.reasonDetail = reasonDetail;
        r.amount = amount;
        r.tossIdempotencyKey = tossIdempotencyKey;
        r.status = RefundStatus.REQUESTED;
        r.requestedAt = requestedAt;
        return r;
    }

    /** 토스 cancelReason (최대 200자). 기타는 상세를 붙인다 */
    public String tossCancelReason() {
        String text = reasonDetail == null ? reason.label() : reason.label() + ": " + reasonDetail;
        return text.length() > 200 ? text.substring(0, 200) : text;
    }

    /** 토스 취소 성공 확인 */
    public void complete(OffsetDateTime canceledAt) {
        changeStatus(RefundStatus.COMPLETED);
        this.canceledAt = canceledAt;
        this.nextSyncAt = null;
    }

    /** 명확한 환불 실패 */
    public void fail(String errorCode, String errorMessage) {
        changeStatus(RefundStatus.FAILED);
        recordError(errorCode, errorMessage);
        this.nextSyncAt = null;
    }

    /** 결과 불명. 곧바로 첫 조회를 하도록 예약한다 (RefundAutoSyncService) */
    public void check(String errorCode, String errorMessage, OffsetDateTime now) {
        changeStatus(RefundStatus.CHECKING);
        recordError(errorCode, errorMessage);
        this.nextSyncAt = now;
    }

    /**
     * 조회 한 번을 기록하고 다음 차례를 정한다. 한도에 닿으면 예약을 지우고 true를 돌려준다.
     * 부르는 쪽이 그때 실패로 확정할지(조회로 취소 안 됨을 확인), 수동 확인으로 남길지 정한다
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

    public void stopSync() {
        this.nextSyncAt = null;
    }

    private void recordError(String errorCode, String errorMessage) {
        this.errorCode = cut(errorCode, ERROR_CODE_MAX);
        this.errorMessage = cut(errorMessage, ERROR_MESSAGE_MAX);
    }

    private void changeStatus(RefundStatus next) {
        if (!status.canChangeTo(next)) {
            throw new IllegalStateException("허용되지 않는 환불 상태 전이다: refundId=" + id + " " + status + " -> " + next);
        }
        this.status = next;
    }

    private static String cut(String value, int max) {
        return value == null || value.length() <= max ? value : value.substring(0, max);
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public int getAttemptNo() { return attemptNo; }
    public CancelRequester getRequestedBy() { return requestedBy; }
    public String getRequesterId() { return requesterId; }
    public CancelReason getReason() { return reason; }
    public String getReasonDetail() { return reasonDetail; }
    public long getAmount() { return amount; }
    public String getTossIdempotencyKey() { return tossIdempotencyKey; }
    public RefundStatus getStatus() { return status; }
    public OffsetDateTime getRequestedAt() { return requestedAt; }
    public String getErrorCode() { return errorCode; }
    public String getErrorMessage() { return errorMessage; }
    public OffsetDateTime getCanceledAt() { return canceledAt; }
    public int getSyncAttempts() { return syncAttempts; }
    public OffsetDateTime getNextSyncAt() { return nextSyncAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
