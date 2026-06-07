package com.jaramgle.backend.controller;

import com.jaramgle.backend.service.publicdata.BusanAttractionSourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/admin/busan/story-sources")
@RequiredArgsConstructor
public class AdminBusanDataController {

    private final BusanAttractionSourceService busanAttractionSourceService;

    @GetMapping("/status")
    public ResponseEntity<BusanAttractionSourceService.StatusResult> status() {
        return ResponseEntity.ok(busanAttractionSourceService.getStatus());
    }

    @PostMapping("/sync")
    public ResponseEntity<BusanAttractionSourceService.SyncResult> sync() {
        return ResponseEntity.ok(busanAttractionSourceService.syncFromPublicData());
    }

    @PostMapping("/sync-photos")
    public ResponseEntity<BusanAttractionSourceService.PhotoEnrichmentResult> syncPhotos(
            @RequestParam(name = "max", defaultValue = "12") int max
    ) {
        return ResponseEntity.ok(busanAttractionSourceService.enrichPhotoData(max));
    }
}
