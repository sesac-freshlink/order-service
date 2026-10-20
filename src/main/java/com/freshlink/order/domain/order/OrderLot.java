package com.freshlink.order.domain.order;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;
import java.time.OffsetDateTime;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

/** 주문에 잡힌 lot (표 order_lot, V1). RESERVED.lots를 그대로 남긴다 */
@Entity
@Table(name = "order_lot")
@EntityListeners(AuditingEntityListener.class)
public class OrderLot {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "order_id", nullable = false)
    private Long orderId;

    @Column(name = "lot_id", nullable = false)
    private Long lotId;

    @Column(name = "quantity", nullable = false)
    private int quantity;

    @Column(name = "expiry_at", nullable = false)
    private OffsetDateTime expiryAt;

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private OffsetDateTime createdAt;

    protected OrderLot() {
    }

    public static OrderLot of(Long orderId, Long lotId, int quantity, OffsetDateTime expiryAt) {
        OrderLot lot = new OrderLot();
        lot.orderId = orderId;
        lot.lotId = lotId;
        lot.quantity = quantity;
        lot.expiryAt = expiryAt;
        return lot;
    }

    public Long getId() { return id; }
    public Long getOrderId() { return orderId; }
    public Long getLotId() { return lotId; }
    public int getQuantity() { return quantity; }
    public OffsetDateTime getExpiryAt() { return expiryAt; }
    public OffsetDateTime getCreatedAt() { return createdAt; }
}
