package com.freshlink.order.domain.order;

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
import java.time.LocalDate;
import java.time.OffsetDateTime;
import java.time.format.DateTimeFormatter;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 주문 (표 orders, V1). 상태 전이는 단계별로 이 클래스 메서드로 한다 */
@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
public class Order {

    /** 결제 대기 시간. 이 시간 안에 결제하지 않으면 만료 스케줄러가 EXPIRED로 닫는다 */
    public static final Duration PAYMENT_TIMEOUT = Duration.ofMinutes(10);

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_no", length = 30, unique = true)
    private String orderNo;

    @Column(name = "customer_id", nullable = false, length = 36)
    private String customerId;

    @Column(name = "request_id", nullable = false, length = 36)
    private String requestId;

    @Column(name = "store_id", nullable = false)
    private Long storeId;

    @Column(name = "store_name", nullable = false, length = 30)
    private String storeName;

    @Column(name = "product_id", nullable = false)
    private Long productId;

    @Column(name = "product_name", length = 100)
    private String productName;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "unit_price")
    private Long unitPrice;

    @Column(name = "amount")
    private Long amount;

    @Enumerated(EnumType.STRING)
    @Column(name = "status", nullable = false, length = 30)
    private OrderStatus status;

    @Enumerated(EnumType.STRING)
    @Column(name = "failure_reason", length = 30)
    private OrderFailureReason failureReason;

    @Column(name = "available_quantity")
    private Integer availableQuantity;

    @Column(name = "payment_waiting_at")
    private OffsetDateTime paymentWaitingAt;

    @Column(name = "payment_expires_at")
    private OffsetDateTime paymentExpiresAt;

    @Column(name = "paid_at")
    private OffsetDateTime paidAt;

    @Column(name = "pickup_deadline")
    private OffsetDateTime pickupDeadline;

    @Column(name = "picked_up_at")
    private OffsetDateTime pickedUpAt;

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

    /** A12 재고 확인 결과를 오래 못 받아 경고를 남긴 시각. 한 주문에 한 번만 경고한다 (V7) */
    @Column(name = "reserving_alerted_at")
    private OffsetDateTime reservingAlertedAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private OffsetDateTime updatedAt;

    protected Order() {
    }

    /** 주문 생성. 상태는 RESERVING이고 나머지 값은 이후 단계에서 채운다 */
    public static Order create(String customerId, String requestId, Long storeId,
                               String storeName, Long productId, int quantity) {
        Order order = new Order();
        order.customerId = customerId;
        order.requestId = requestId;
        order.storeId = storeId;
        order.storeName = storeName;
        order.productId = productId;
        order.quantity = quantity;
        order.status = OrderStatus.RESERVING;
        return order;
    }

    /** 주문번호 FL-yyyyMMdd-000123 (id가 6자리를 넘으면 자릿수가 늘어난다) */
    public static String formatOrderNo(Long id, LocalDate date) {
        return String.format("FL-%s-%06d", date.format(DateTimeFormatter.BASIC_ISO_DATE), id);
    }

    /** 주문번호는 한 번만 정한다 */
    public void assignOrderNo(String orderNo) {
        if (this.orderNo != null) {
            throw new IllegalStateException("주문번호가 이미 있다: " + this.orderNo);
        }
        this.orderNo = orderNo;
    }

    /** 같은 Idempotency-Key 재요청이 처음 요청과 같은 내용인지 본다 (점포 이름은 비교하지 않는다) */
    public boolean hasSameContent(Long storeId, Long productId, int quantity) {
        return this.storeId.equals(storeId) && this.productId.equals(productId) && this.quantity == quantity;
    }

    /** RESERVED 반영: 상품명·단가를 스냅샷으로 남기고 금액을 확정해 결제 대기로 바꾼다 */
    public void reserve(String productName, long unitPrice, OffsetDateTime now) {
        requireReserving();
        this.productName = productName;
        this.unitPrice = unitPrice;
        this.amount = unitPrice * quantity;
        changeStatus(OrderStatus.PAYMENT_WAITING);
        this.paymentWaitingAt = now;
        this.paymentExpiresAt = now.plus(PAYMENT_TIMEOUT);
    }

    /** RESERVE_FAILED 반영: 재고 확보 실패로 주문을 닫는다 */
    public void rejectReservation(OrderFailureReason reason, int availableQuantity, OffsetDateTime now) {
        requireReserving();
        changeStatus(OrderStatus.OUT_OF_STOCK);
        this.failureReason = reason;
        this.availableQuantity = availableQuantity;
        this.closedAt = now;
    }

    /**
     * OR-03 승인 시작: PAYMENT_WAITING이고 마감 전일 때만 PAYMENT_APPROVING으로 바꾼다.
     * 행을 잠근 채(findByIdForUpdate) 부르므로 만료 스케줄러와 동시에 확정되지 않는다
     */
    public void startApproval(OffsetDateTime now) {
        if (status != OrderStatus.PAYMENT_WAITING || paymentExpiresAt == null || !now.isBefore(paymentExpiresAt)) {
            throw new IllegalStateException("승인을 시작할 수 없다: orderId=" + id + " status=" + status);
        }
        changeStatus(OrderStatus.PAYMENT_APPROVING);
    }

    /** 토스 승인 성공 */
    public void markPaid(OffsetDateTime paidAt) {
        requireApproving();
        changeStatus(OrderStatus.PAID);
        this.paidAt = paidAt;
    }

    /** 토스 확정 실패. 재고는 RELEASE로 돌려준다 */
    public void failPayment(OffsetDateTime now) {
        requireApproving();
        changeStatus(OrderStatus.PAYMENT_FAILED);
        this.failureReason = OrderFailureReason.PAYMENT_FAILED;
        this.closedAt = now;
    }

    /** 결제 대기 10분이 지나면 EXPIRED로 닫는다 (만료 스케줄러). 재고는 RELEASE(EXPIRED)로 돌려준다 */
    public void expire(OffsetDateTime now) {
        if (status != OrderStatus.PAYMENT_WAITING || paymentExpiresAt == null || now.isBefore(paymentExpiresAt)) {
            throw new IllegalStateException("만료할 수 없다: orderId=" + id + " status=" + status);
        }
        changeStatus(OrderStatus.EXPIRED);
        this.failureReason = OrderFailureReason.EXPIRED;
        this.closedAt = now;
    }

    /** A4 픽업 기한: RESERVED로 받은 lot 유통기한 중 가장 빠른 값 (결제 대기로 바뀐 직후 한 번) */
    public void assignPickupDeadline(OffsetDateTime deadline) {
        if (status != OrderStatus.PAYMENT_WAITING) {
            throw new IllegalStateException("픽업 기한은 결제 대기에서 정한다: orderId=" + id + " status=" + status);
        }
        this.pickupDeadline = deadline;
    }

    /** 픽업 기한이 지났는지. 기한 시각까지는 픽업·결제할 수 있다 */
    public boolean isPickupDeadlinePassed(OffsetDateTime now) {
        return pickupDeadline != null && now.isAfter(pickupDeadline);
    }

    /** A5 점주 픽업 확인: 결제 완료이고 기한 이내일 때만. 되돌리기는 없다 */
    public void pickUp(OffsetDateTime now) {
        if (status != OrderStatus.PAID || isPickupDeadlinePassed(now)) {
            throw new IllegalStateException("픽업할 수 없다: orderId=" + id + " status=" + status);
        }
        changeStatus(OrderStatus.PICKED_UP);
        this.pickedUpAt = now;
    }

    /**
     * A4 픽업 기한 경과 (스케줄러). 결제 완료면 PICKUP_EXPIRED(환불·재고 복원 없음),
     * 결제 대기면 EXPIRED·사유 PICKUP_DEADLINE (재고는 부르는 쪽이 RELEASE로 돌려준다)
     */
    public void expirePickup(OffsetDateTime now) {
        if (!isPickupDeadlinePassed(now)) {
            throw new IllegalStateException("픽업 기한 전이다: orderId=" + id);
        }
        if (status == OrderStatus.PAID) {
            changeStatus(OrderStatus.PICKUP_EXPIRED);
        } else if (status == OrderStatus.PAYMENT_WAITING) {
            changeStatus(OrderStatus.EXPIRED);
            this.failureReason = OrderFailureReason.PICKUP_DEADLINE;
        } else {
            throw new IllegalStateException("픽업 기한으로 닫을 수 없다: orderId=" + id + " status=" + status);
        }
        this.closedAt = now;
    }

    /** OP-02 재고 확보 결과가 오지 않아 RESERVING에 멈춘 주문을 닫는다. 재고는 RELEASE(EXPIRED)로 돌려준다 */
    public void closeStuck(OffsetDateTime now) {
        requireReserving();
        changeStatus(OrderStatus.EXPIRED);
        this.failureReason = OrderFailureReason.EXPIRED;
        this.closedAt = now;
    }

    /** 상태는 이 메서드로만 바꾼다. 허용되는 전이는 OrderStatus 표 한곳에서 정한다 */
    private void changeStatus(OrderStatus next) {
        if (!status.canChangeTo(next)) {
            throw new IllegalStateException("허용되지 않는 상태 전이다: orderId=" + id + " " + status + " -> " + next);
        }
        this.status = next;
    }

    private void requireApproving() {
        if (status != OrderStatus.PAYMENT_APPROVING) {
            throw new IllegalStateException("결제 결과는 PAYMENT_APPROVING에서만 반영한다: orderId=" + id + " status=" + status);
        }
    }

    private void requireReserving() {
        if (status != OrderStatus.RESERVING) {
            throw new IllegalStateException("예약 결과는 RESERVING에서만 반영한다: orderId=" + id + " status=" + status);
        }
    }

    public Long getId() { return id; }
    public String getOrderNo() { return orderNo; }
    public String getCustomerId() { return customerId; }
    public String getRequestId() { return requestId; }
    public Long getStoreId() { return storeId; }
    public String getStoreName() { return storeName; }
    public Long getProductId() { return productId; }
    public String getProductName() { return productName; }
    public int getQuantity() { return quantity; }
    public Long getUnitPrice() { return unitPrice; }
    public Long getAmount() { return amount; }
    public OrderStatus getStatus() { return status; }
    public OrderFailureReason getFailureReason() { return failureReason; }
    public Integer getAvailableQuantity() { return availableQuantity; }
    public OffsetDateTime getPaymentWaitingAt() { return paymentWaitingAt; }
    public OffsetDateTime getPaymentExpiresAt() { return paymentExpiresAt; }
    public OffsetDateTime getPaidAt() { return paidAt; }
    public OffsetDateTime getPickupDeadline() { return pickupDeadline; }
    public OffsetDateTime getPickedUpAt() { return pickedUpAt; }
    /** A12 재고 확인 중(RESERVING) 경고를 남겼다고 표시한다. 상태는 바꾸지 않는다 */
    public void markReservingAlerted(OffsetDateTime now) {
        if (status != OrderStatus.RESERVING) {
            throw new IllegalStateException("재고 확인 중이 아니다: orderId=" + id + " status=" + status);
        }
        this.reservingAlertedAt = now;
    }

    public OffsetDateTime getClosedAt() { return closedAt; }
    public OffsetDateTime getReservingAlertedAt() { return reservingAlertedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
