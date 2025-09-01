package com.findme.backend.controller;

import com.findme.backend.dto.TestDefImportRequest;
import com.findme.backend.dto.TestDefListItem;
import com.findme.backend.dto.TestDefResponse;
import com.findme.backend.service.TestDefService;
import lombok.RequiredArgsConstructor;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

import java.util.List;

@RestController
@RequestMapping("/api/admin/tests")
@RequiredArgsConstructor
public class AdminTestController {

    private final TestDefService testDefService;

    @Value("${admin.token}") // Injected from application.yml or environment
    private String adminToken;

    // Simple token-based security for admin endpoints
    private void validateAdminToken(String token) {
        if (token == null || !token.equals(adminToken)) {
            throw new IllegalArgumentException("Unauthorized: Invalid admin token.");
        }
    }

    @PostMapping("/import")
    public ResponseEntity<TestDefResponse> importTestDef(
                @RequestHeader("X-Admin-Token") String token,
                @RequestBody TestDefImportRequest request) {
        validateAdminToken(token);
        TestDefResponse response = testDefService.importTestDef(request, "admin"); // Actor can be dynamic
        return ResponseEntity.status(HttpStatus.CREATED).body(response);
    }

    @PostMapping("/{code}/{version}/publish")
    public ResponseEntity<TestDefResponse> publishTestDef(
                @RequestHeader("X-Admin-Token") String token,
                @PathVariable String code,
                @PathVariable int version) {
        validateAdminToken(token);
        TestDefResponse response = testDefService.publishTestDef(code, version, "admin"); // Actor can be dynamic
        return ResponseEntity.ok(response);
    }

    @GetMapping
    public ResponseEntity<List<TestDefListItem>> getTestDefs(
                @RequestHeader("X-Admin-Token") String token,
                @RequestParam(required = false) String code) {
        validateAdminToken(token);
        List<TestDefListItem> testDefs = testDefService.getTestDefs(code);
        return ResponseEntity.ok(testDefs);
    }

    @GetMapping("/{code}/{version}")
    public ResponseEntity<TestDefResponse> getTestDef(
                @RequestHeader("X-Admin-Token") String token,
                @PathVariable String code,
                @PathVariable int version) {
        validateAdminToken(token);
        TestDefResponse testDef = testDefService.getTestDef(code, version);
        return ResponseEntity.ok(testDef);
    }

    @GetMapping("/{code}/latest")
    public ResponseEntity<TestDefResponse> getLatestPublishedTestDef(
                @RequestHeader("X-Admin-Token") String token,
                @PathVariable String code) {
        validateAdminToken(token);
        TestDefResponse testDef = testDefService.getLatestPublishedTestDef(code)
                    .orElseThrow(() -> new IllegalArgumentException("No published test found for code: " + code));
        return ResponseEntity.ok(testDef);
    }
}
