package com.jaramgle.backend.dto.publicdata;

public record BusanAttractionSourceDto(
        String sourceId,
        String title,
        String district,
        String subtitle,
        String intro,
        String feature,
        String origin,
        String storyContext,
        String address,
        String thumbnailUrl,
        String imageUrl
) {}
