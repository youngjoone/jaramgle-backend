package com.findme.backend.service;

import com.findme.backend.dto.TestDefImportRequest;
import com.findme.backend.dto.TestDefListItem;
import com.findme.backend.dto.TestDefResponse;
import com.findme.backend.entity.TestAudit;
import com.findme.backend.entity.TestDef;
import com.findme.backend.repository.TestAuditRepository;
import com.findme.backend.repository.TestDefRepository;
import com.fasterxml.jackson.databind.ObjectMapper; // For JSON snapshot
import lombok.RequiredArgsConstructor;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import java.time.LocalDateTime;
import java.util.List;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TestDefService {

    private final TestDefRepository testDefRepository;
    private final TestAuditRepository testAuditRepository;
    private final ObjectMapper objectMapper; // For JSON snapshot

    @Transactional
    public TestDefResponse importTestDef(TestDefImportRequest request, String actor) {
        // Check for existing code and version
        Optional<TestDef> existingTestDef = testDefRepository.findByCodeAndVersion(request.getCode(), request.getVersion());
        if (existingTestDef.isPresent()) {
            throw new IllegalArgumentException("Test definition with code " + request.getCode() + " and version " + request.getVersion() + " already exists.");
        }

        TestDef testDef = new TestDef(
                null, // ID will be generated
                request.getCode(),
                request.getVersion(),
                request.getTitle(),
                "DRAFT", // Initial status
                request.getQuestions(),
                request.getScoring(),
                LocalDateTime.now(),
                LocalDateTime.now()
        );
        testDefRepository.save(testDef);

        // Record audit
        recordAudit(testDef, "IMPORT", actor);

        return convertToResponseDto(testDef);
    }

    @Transactional
    public TestDefResponse publishTestDef(String code, int version, String actor) {
        TestDef testDef = testDefRepository.findByCodeAndVersion(code, version)
                .orElseThrow(() -> new IllegalArgumentException("Test definition not found: " + code + " v" + version));

        if (!"DRAFT".equals(testDef.getStatus())) {
            throw new IllegalArgumentException("Only DRAFT test definitions can be published.");
        }

        // Archive any existing PUBLISHED versions of the same code
        testDefRepository.findByCodeAndStatus(code, "PUBLISHED").ifPresent(published -> {
            published.setStatus("ARCHIVED");
            published.setUpdatedAt(LocalDateTime.now());
            testDefRepository.save(published);
            recordAudit(published, "ARCHIVE", actor);
        });

        testDef.setStatus("PUBLISHED");
        testDef.setUpdatedAt(LocalDateTime.now());
        testDefRepository.save(testDef);

        // Record audit
        recordAudit(testDef, "PUBLISH", actor);

        return convertToResponseDto(testDef);
    }

    public List<TestDefListItem> getTestDefs(String code) {
        List<TestDef> testDefs;
        if (code != null && !code.isEmpty()) {
            testDefs = testDefRepository.findByCodeOrderByVersionDesc(code);
        } else {
            testDefs = testDefRepository.findAll(); // Or paginate, for simplicity
        }
        return testDefs.stream()
                .map(this::convertToListDto)
                .collect(Collectors.toList());
    }

    public TestDefResponse getTestDef(String code, int version) {
        TestDef testDef = testDefRepository.findByCodeAndVersion(code, version)
                .orElseThrow(() -> new IllegalArgumentException("Test definition not found: " + code + " v" + version));
        return convertToResponseDto(testDef);
    }

    public Optional<TestDefResponse> getLatestPublishedTestDef(String code) {
        return testDefRepository.findByCodeAndStatus(code, "PUBLISHED")
                .map(this::convertToResponseDto);
    }

    private void recordAudit(TestDef testDef, String action, String actor) {
        try {
            String snapshot = objectMapper.writeValueAsString(testDef);
            TestAudit audit = new TestAudit(
                    null,
                    testDef.getCode(),
                    testDef.getVersion(),
                    action,
                    actor,
                    snapshot,
                    LocalDateTime.now()
            );
            testAuditRepository.save(audit);
        } catch (Exception e) {
            System.err.println("Failed to record audit for testDef " + testDef.getCode() + " v" + testDef.getVersion() + ": " + e.getMessage());
        }
    }

    private TestDefResponse convertToResponseDto(TestDef testDef) {
        return new TestDefResponse(
                testDef.getId(),
                testDef.getCode(),
                testDef.getVersion(),
                testDef.getTitle(),
                testDef.getStatus(),
                testDef.getQuestions(),
                testDef.getScoring(),
                testDef.getCreatedAt(),
                testDef.getUpdatedAt()
        );
    }

    private TestDefListItem convertToListDto(TestDef testDef) {
        return new TestDefListItem(
                testDef.getId(),
                testDef.getCode(),
                testDef.getVersion(),
                testDef.getTitle(),
                testDef.getStatus(),
                testDef.getCreatedAt(),
                testDef.getUpdatedAt()
        );
    }
}
