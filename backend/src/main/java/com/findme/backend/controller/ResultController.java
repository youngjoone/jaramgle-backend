package com.findme.backend.controller;

import com.findme.backend.auth.CustomOAuth2User;
import com.findme.backend.dto.PaginatedResponse;
import com.findme.backend.dto.ResultDetailDto;
import com.findme.backend.dto.ResultListItemDto;
import com.findme.backend.entity.ResultEntity;
import com.findme.backend.repository.ResultRepository;
import com.findme.backend.repository.EntitlementRepository; // Import EntitlementRepository
import lombok.RequiredArgsConstructor;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal; // Import AuthenticationPrincipal
import org.springframework.web.bind.annotation.*;

import java.util.List;
import java.util.stream.Collectors;

@RestController
@RequestMapping("/api") // Changed to /api to allow /api/results and /api/download
@RequiredArgsConstructor
public class ResultController {

    private final ResultRepository resultRepository;
    private final EntitlementRepository entitlementRepository; // Inject EntitlementRepository

    @GetMapping("/results") // Changed to /api/results
    public ResponseEntity<PaginatedResponse<ResultListItemDto>> getResults(
            @RequestParam(defaultValue = "0") int page,
            @RequestParam(defaultValue = "10") int size) {

        Pageable pageable = PageRequest.of(page, size, Sort.by("createdAt").descending());
        Page<ResultEntity> resultsPage = resultRepository.findAll(pageable);

        List<ResultListItemDto> items = resultsPage.getContent().stream()
                .map(result -> new ResultListItemDto(result.getId(), result.getTestCode(), result.getScore(), result.getCreatedAt()))
                .collect(Collectors.toList());

        String nextCursor = null;
        if (resultsPage.hasNext()) {
            nextCursor = String.valueOf(page + 1); // Simple page number as cursor
        }

        return ResponseEntity.ok(new PaginatedResponse<>(items, nextCursor));
    }

    @GetMapping("/results/{id}") // Changed to /api/results/{id}
    public ResponseEntity<ResultDetailDto> getResultDetail(@PathVariable Long id) {
        return resultRepository.findById(id)
                .map(result -> new ResultDetailDto(
                        result.getId(),
                        result.getTestCode(),
                        result.getScore(),
                        result.getTraits(), // JSON string
                        result.getPoem(),
                        result.getCreatedAt()
                ))
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @GetMapping("/download/{resultId}")
    public ResponseEntity<String> downloadResult(
            @PathVariable Long resultId,
            @RequestParam String quality,
            @AuthenticationPrincipal CustomOAuth2User principal) {

        // Check if result exists
        if (resultRepository.findById(resultId).isEmpty()) {
            return ResponseEntity.notFound().build();
        }

        if ("free".equalsIgnoreCase(quality)) {
            // Always allow free download with watermark
            return ResponseEntity.ok("http://localhost:8080/placeholder-watermarked-image.png");
        } else if ("hires".equalsIgnoreCase(quality)) {
            Long userId = (principal != null) ? principal.getId() : null;

            if (userId == null) {
                // Not logged in, return 402
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body("{\"code\":\"PAYMENT_REQUIRED\", \"message\":\"로그인 후 고해상도 다운로드가 가능합니다.\"}");
            }

            // Check for entitlement
            boolean hasEntitlement = entitlementRepository.findByUserIdAndItemCode(userId, "hires_download").isPresent();

            if (hasEntitlement) {
                // Return placeholder high-res image URL
                return ResponseEntity.ok("http://localhost:8080/placeholder-highres-image.png");
            } else {
                // Entitlement not found, return 402
                return ResponseEntity.status(HttpStatus.PAYMENT_REQUIRED)
                        .body("{\"code\":\"PAYMENT_REQUIRED\", \"message\":\"고해상도 다운로드 권한이 필요합니다.\"}");
            }
        } else {
            return ResponseEntity.badRequest().body("Invalid quality parameter.");
        }
    }
}