package com.jaramgle.backend.controller;

import com.jaramgle.backend.dto.publicdata.BusanAttractionPageDto;
import com.jaramgle.backend.dto.publicdata.LocalStorySourcePageDto;
import com.jaramgle.backend.service.publicdata.BusanAttractionSourceService;
import com.jaramgle.backend.service.publicdata.LocalStorySourceService;
import lombok.RequiredArgsConstructor;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import java.util.Map;

@RestController
@RequestMapping("/api/public")
@RequiredArgsConstructor
public class PublicController {

    private final BusanAttractionSourceService busanAttractionSourceService;
    private final LocalStorySourceService localStorySourceService;

    @GetMapping("/ping")
    public ResponseEntity<Map<String, Boolean>> ping() {
        return ResponseEntity.ok(Map.of("pong", true));
    }

    @GetMapping("/busan/attractions")
    public ResponseEntity<BusanAttractionPageDto> getBusanAttractions(
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "12") int size,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "sourceId", required = false) String sourceId
    ) {
        return ResponseEntity.ok(busanAttractionSourceService.getAttractions(page, size, query, sourceId));
    }

    @GetMapping("/local/{region}/sources")
    public ResponseEntity<LocalStorySourcePageDto> getLocalStorySources(
            @org.springframework.web.bind.annotation.PathVariable String region,
            @RequestParam(name = "page", defaultValue = "1") int page,
            @RequestParam(name = "size", defaultValue = "12") int size,
            @RequestParam(name = "q", required = false) String query,
            @RequestParam(name = "sourceId", required = false) String sourceId
    ) {
        return ResponseEntity.ok(localStorySourceService.getSources(region, page, size, query, sourceId));
    }
}
