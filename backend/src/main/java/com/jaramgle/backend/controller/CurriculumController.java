package com.jaramgle.backend.controller;

import com.jaramgle.backend.auth.AuthPrincipal;
import com.jaramgle.backend.dto.curriculum.CreateCurriculumRequest;
import com.jaramgle.backend.dto.curriculum.CurriculumActionResponse;
import com.jaramgle.backend.dto.curriculum.CurriculumDetailDto;
import com.jaramgle.backend.dto.curriculum.CurriculumGoalDraftRequest;
import com.jaramgle.backend.dto.curriculum.CurriculumGoalDraftResponse;
import com.jaramgle.backend.dto.curriculum.CurriculumJobDto;
import com.jaramgle.backend.dto.curriculum.CurriculumSummaryDto;
import com.jaramgle.backend.dto.curriculum.UpdateWeekGoalRequest;
import com.jaramgle.backend.dto.curriculum.WeekGenerationRequest;
import com.jaramgle.backend.service.CurriculumGoalDraftService;
import com.jaramgle.backend.service.CurriculumService;
import jakarta.validation.Valid;
import lombok.RequiredArgsConstructor;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.security.core.annotation.AuthenticationPrincipal;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PatchMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.List;

@RestController
@RequestMapping("/api/curriculums")
@RequiredArgsConstructor
public class CurriculumController {

    private final CurriculumService curriculumService;
    private final CurriculumGoalDraftService curriculumGoalDraftService;

    @GetMapping
    public ResponseEntity<List<CurriculumSummaryDto>> list(@AuthenticationPrincipal AuthPrincipal principal) {
        return ResponseEntity.ok(curriculumService.getCurriculums(String.valueOf(principal.id())));
    }

    @PostMapping("/goal-drafts")
    public ResponseEntity<CurriculumGoalDraftResponse> draftGoals(@Valid @RequestBody CurriculumGoalDraftRequest request) {
        return ResponseEntity.ok(curriculumGoalDraftService.draftGoals(request));
    }

    @PostMapping
    public ResponseEntity<CurriculumDetailDto> create(
            @Valid @RequestBody CreateCurriculumRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        CurriculumDetailDto created = curriculumService.createCurriculum(String.valueOf(principal.id()), request);
        return new ResponseEntity<>(created, HttpStatus.CREATED);
    }

    @GetMapping("/{id}")
    public ResponseEntity<CurriculumDetailDto> detail(
            @PathVariable Long id,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.getCurriculumDetail(String.valueOf(principal.id()), id));
    }

    @PatchMapping("/{id}/weeks/{weekNo}/goal")
    public ResponseEntity<CurriculumActionResponse> updateGoal(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @Valid @RequestBody UpdateWeekGoalRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.updateWeekGoal(String.valueOf(principal.id()), id, weekNo, request));
    }

    @PostMapping("/{id}/weeks/{weekNo}/generate")
    public ResponseEntity<CurriculumActionResponse> generate(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @RequestBody(required = false) WeekGenerationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.requestWeekGenerate(String.valueOf(principal.id()), id, weekNo, request));
    }

    @PostMapping("/{id}/weeks/{weekNo}/retry")
    public ResponseEntity<CurriculumActionResponse> retry(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @RequestBody(required = false) WeekGenerationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.requestWeekRetry(String.valueOf(principal.id()), id, weekNo, request));
    }

    @PostMapping("/{id}/weeks/{weekNo}/regenerate")
    public ResponseEntity<CurriculumActionResponse> regenerate(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @RequestBody(required = false) WeekGenerationRequest request,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.requestWeekRegenerate(String.valueOf(principal.id()), id, weekNo, request));
    }

    @PostMapping("/{id}/weeks/{weekNo}/cancel")
    public ResponseEntity<CurriculumActionResponse> cancel(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.cancelPendingJob(String.valueOf(principal.id()), id, weekNo));
    }

    @GetMapping("/{id}/weeks/{weekNo}/jobs/latest")
    public ResponseEntity<CurriculumJobDto> latestJob(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @AuthenticationPrincipal AuthPrincipal principal
    ) {
        return ResponseEntity.ok(curriculumService.getLatestWeekJob(String.valueOf(principal.id()), id, weekNo));
    }
}
