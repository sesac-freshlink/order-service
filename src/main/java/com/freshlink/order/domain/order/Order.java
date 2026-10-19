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
import java.time.OffsetDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 주문 (표 orders, V1). 상태 전이는 Saga 작업에서 추가한다 */
@Entity
@Table(name = "orders")
@EntityListeners(AuditingEntityListener.class)
public class Order {

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

    @Column(name = "closed_at")
    private OffsetDateTime closedAt;

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
    public OffsetDateTime getClosedAt() { return closedAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
    public OffsetDateTime getUpdatedAt() { return updatedAt; }
}
