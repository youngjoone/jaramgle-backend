package com.fairylearn.backend.service;

import com.fairylearn.backend.auth.CustomOAuth2User;
import com.fairylearn.backend.dto.MockPaymentRequest;
import com.fairylearn.backend.dto.MockPaymentResponse;
import com.fairylearn.backend.entity.Entitlement;
import com.fairylearn.backend.entity.Purchase;
import com.fairylearn.backend.entity.User; // Import User
import com.fairylearn.backend.repository.EntitlementRepository;
import com.fairylearn.backend.repository.PurchaseRepository;
import com.fairylearn.backend.repository.UserRepository; // Import UserRepository
import lombok.RequiredArgsConstructor;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.userdetails.UserDetails; // Import UserDetails
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.Optional;

@Service
@RequiredArgsConstructor
public class BillingService {

    private final PurchaseRepository purchaseRepository;
    private final EntitlementRepository entitlementRepository;
    private final UserRepository userRepository; // Inject UserRepository

    @Transactional
    public MockPaymentResponse processMockPayment(MockPaymentRequest request) {
        Long userId = getCurrentUserId();

        if (userId == null) {
            throw new IllegalArgumentException("User must be logged in for mock payment.");
        }

        // 1. Save purchase record
        Purchase purchase = new Purchase(
                null, // ID will be generated
                userId,
                request.getItemCode(),
                request.getAmount(),
                "PAID", // Always PAID for mock payment
                LocalDateTime.now()
        );
        purchaseRepository.save(purchase);

        // 2. Create or update entitlement
        Optional<Entitlement> existingEntitlement = entitlementRepository.findByUserIdAndItemCode(userId, request.getItemCode());
        Entitlement entitlement;
        if (existingEntitlement.isPresent()) {
            entitlement = existingEntitlement.get();
            entitlement.setExpiresAt(null); // Make it permanent if it was temporary
            entitlement.setCreatedAt(LocalDateTime.now()); // Update creation time
        } else {
            entitlement = new Entitlement(
                    null, // ID will be generated
                    userId,
                    request.getItemCode(),
                    null, // Permanent entitlement
                    LocalDateTime.now()
            );
        }
        entitlementRepository.save(entitlement);

        return new MockPaymentResponse(purchase.getId(), "PAID");
    }

    private Long getCurrentUserId() {
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.isAuthenticated()) { // Check if authenticated
            Object principal = authentication.getPrincipal();
            if (principal instanceof UserDetails) {
                String email = ((UserDetails) principal).getUsername();
                User user = userRepository.findByEmail(email)
                        .orElseThrow(() -> new IllegalArgumentException("User not found with email: " + email));
                return user.getId();
            } else if (principal instanceof CustomOAuth2User) {
                CustomOAuth2User oauth2Principal = (CustomOAuth2User) principal;
                return oauth2Principal.getId();
            }
        }
        return null;
    }
}
