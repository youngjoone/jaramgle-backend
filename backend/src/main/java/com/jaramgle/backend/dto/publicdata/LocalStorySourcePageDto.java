package com.jaramgle.backend.dto.publicdata;

import java.util.List;

public record LocalStorySourcePageDto(
        List<LocalStorySourceDto> items,
        int page,
        int size,
        long totalCount,
        boolean hasNext
) {}
