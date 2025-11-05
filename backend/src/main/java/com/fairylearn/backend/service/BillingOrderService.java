package com.fairylearn.backend.service;

import com.fairylearn.backend.entity.BillingOrder;
import com.fairylearn.backend.entity.BillingOrderStatus;
import com.fairylearn.backend.entity.HeartProduct;
import com.fairylearn.backend.entity.HeartTransactionType;
import com.fairylearn.backend.repository.BillingOrderRepository;
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Objects;

@Service
@RequiredArgsConstructor
public class BillingOrderService {

    private final BillingOrderRepository billingOrderRepository;
    private final HeartProductService heartProductService;
    private final HeartWalletService heartWalletService;

    @Transactional
    public BillingOrder createOrder(Long userId, String productCode, int quantity) {
        HeartProduct product = heartProductService.getProduct(productCode);
        if (!product.isActive()) {
            throw new IllegalStateException("판매 중지된 상품입니다.");
        }
        int normalizedQuantity = Math.max(1, quantity);

        BillingOrder order = new BillingOrder();
        order.setUserId(userId);
        order.setProductCode(product.getCode());
        order.setProduct(product);
        order.setQuantity(normalizedQuantity);
        order.setPricePerUnit(product.getPrice());
        order.setHeartsPerUnit(product.getHearts());
        order.setBonusHeartsPerUnit(product.getBonusHearts());
        order.setTotalAmount(product.getPrice() * normalizedQuantity);
        order.setStatus(BillingOrderStatus.PENDING);
        return billingOrderRepository.save(order);
    }

    @Transactional
    public BillingOrder confirmPayment(Long userId, Long orderId, String paymentKey, String pgProvider) {
        BillingOrder order = billingOrderRepository.findByIdAndUserId(orderId, userId)
                .orElseThrow(() -> new IllegalArgumentException("주문을 찾을 수 없습니다."));

        if (order.getProduct() == null) {
            order.setProduct(heartProductService.getProduct(order.getProductCode()));
        }

        if (order.getStatus() != BillingOrderStatus.PENDING) {
            throw new IllegalStateException("이미 처리된 주문입니다.");
        }

        order.setStatus(BillingOrderStatus.PAID);
        order.setPaymentKey(Objects.requireNonNullElseGet(paymentKey, () -> "TEST-" + order.getId()));
        order.setPgProvider(Objects.requireNonNullElse(pgProvider, "MOCK"));
        order.setPaidAt(LocalDateTime.now());
        BillingOrder saved = billingOrderRepository.save(order);

        int grantedHearts = (saved.getHeartsPerUnit() + saved.getBonusHeartsPerUnit()) * saved.getQuantity();
        heartWalletService.chargeHearts(
                userId,
                grantedHearts,
                HeartTransactionType.CHARGE,
                "하트 충전 - 주문 #" + saved.getId(),
                saved,
                Map.of(
                        "orderId", saved.getId(),
                        "productCode", saved.getProductCode(),
                        "quantity", saved.getQuantity()
                )
        );
        return saved;
    }

    @Transactional(readOnly = true)
    public List<BillingOrder> getRecentOrders(Long userId, int limit) {
        return billingOrderRepository.findTop10ByUserIdOrderByRequestedAtDesc(userId)
                .stream()
                .limit(limit)
                .toList();
    }

    @Transactional(readOnly = true)
    public Page<BillingOrder> getOrders(Long userId, Pageable pageable) {
        return billingOrderRepository.findByUserIdOrderByRequestedAtDesc(userId, pageable);
    }
}
