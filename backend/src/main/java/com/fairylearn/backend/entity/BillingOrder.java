package com.fairylearn.backend.entity;

import jakarta.persistence.*;
import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

import java.time.LocalDateTime;

@Entity
@Table(name = "billing_orders")
@Getter
@Setter
@NoArgsConstructor
public class BillingOrder {

    @Id
    @GeneratedValue(strategy = GenerationType.IDENTITY)
    private Long id;

    @Column(name = "user_id", nullable = false)
    private Long userId;

    @Column(name = "product_code", nullable = false, length = 64)
    private String productCode;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "product_code", referencedColumnName = "code", insertable = false, updatable = false)
    private HeartProduct product;

    @Column(nullable = false)
    private int quantity;

    @Column(name = "price_per_unit", nullable = false)
    private int pricePerUnit;

    @Column(name = "hearts_per_unit", nullable = false)
    private int heartsPerUnit;

    @Column(name = "bonus_hearts_per_unit", nullable = false)
    private int bonusHeartsPerUnit;

    @Column(name = "total_amount", nullable = false)
    private int totalAmount;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 32)
    private BillingOrderStatus status = BillingOrderStatus.PENDING;

    @Column(name = "payment_key")
    private String paymentKey;

    @Column(name = "pg_provider")
    private String pgProvider;

    @Column(name = "requested_at", nullable = false)
    private LocalDateTime requestedAt = LocalDateTime.now();

    @Column(name = "paid_at")
    private LocalDateTime paidAt;

    @Column(name = "canceled_at")
    private LocalDateTime canceledAt;

    @Column(columnDefinition = "TEXT")
    private String metadata;

    public int grantableHearts() {
        return (heartsPerUnit + bonusHeartsPerUnit) * quantity;
    }
}
