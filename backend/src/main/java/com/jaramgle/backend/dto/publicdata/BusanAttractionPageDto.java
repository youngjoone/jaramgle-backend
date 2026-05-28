package com.jaramgle.backend.dto.publicdata;

import java.util.List;

public record BusanAttractionPageDto(
        List<BusanAttractionSourceDto> items,
        int page,
        int size,
        Long totalCount,
        boolean hasNext
) {}
