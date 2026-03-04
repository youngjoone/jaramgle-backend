package com.jaramgle.backend.controller;

import com.jaramgle.backend.dto.curriculum.CurriculumActionResponse;
import com.jaramgle.backend.dto.curriculum.WeekSkipRequest;
import com.jaramgle.backend.service.CurriculumService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/curriculums")
@RequiredArgsConstructor
public class AdminCurriculumController {

    private final CurriculumService curriculumService;

    @PostMapping("/{id}/weeks/{weekNo}/skip")
    public ResponseEntity<CurriculumActionResponse> skipWeek(
            @PathVariable Long id,
            @PathVariable Integer weekNo,
            @RequestBody(required = false) WeekSkipRequest request
    ) {
        String reason = request == null ? null : request.getReason();
        return ResponseEntity.ok(curriculumService.adminSkipWeek(id, weekNo, reason));
    }
}
