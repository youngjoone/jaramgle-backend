package com.jaramgle.backend.controller;

import com.jaramgle.backend.service.publicdata.LocalStorySourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/local/{region}/story-sources")
@RequiredArgsConstructor
public class AdminLocalDataController {

    private final LocalStorySourceService localStorySourceService;

    @GetMapping("/status")
    public ResponseEntity<LocalStorySourceService.StatusResult> status(@PathVariable String region) {
        return ResponseEntity.ok(localStorySourceService.getStatus(region));
    }

    @PostMapping("/sync")
    public ResponseEntity<LocalStorySourceService.SyncResult> sync(@PathVariable String region) {
        return ResponseEntity.ok(localStorySourceService.syncFromPublicData(region));
    }

    @PostMapping("/sync-photos")
    public ResponseEntity<LocalStorySourceService.PhotoEnrichmentResult> syncPhotos(
            @PathVariable String region,
            @RequestParam(name = "max", defaultValue = "12") int max
    ) {
        return ResponseEntity.ok(localStorySourceService.enrichPhotoData(region, max));
    }
}
