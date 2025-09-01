package com.findme.backend.controller;

import com.findme.backend.dto.ResultDto;
import com.findme.backend.dto.SubmissionDto;
import com.findme.backend.dto.TestResponseDto;
import com.findme.backend.service.TestService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.*;

@RestController
@RequestMapping("/api/tests")
@RequiredArgsConstructor
public class TestController {

    private final TestService testService;

    @GetMapping("/{testCode}")
    public ResponseEntity<TestResponseDto> getTest(@PathVariable String testCode) {
        return testService.getTestByCode(testCode)
                .map(ResponseEntity::ok)
                .orElse(ResponseEntity.notFound().build());
    }

    @PostMapping("/{testCode}/submit")
    public ResponseEntity<ResultDto> submitTest(@PathVariable String testCode, @Valid @RequestBody SubmissionDto submission) {
        // A real app would check if the test exists before calculating
        ResultDto result = testService.calculateResult(testCode, submission);
        return ResponseEntity.ok(result);
    }
}
