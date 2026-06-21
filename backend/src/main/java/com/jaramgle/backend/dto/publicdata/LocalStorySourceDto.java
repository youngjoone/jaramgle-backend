package com.jaramgle.backend.dto.publicdata;

public record LocalStorySourceDto(
        String regionCode,
        String sourceId,
        String contentTypeId,
        String title,
        String district,
        String subtitle,
        String intro,
        String feature,
        String origin,
        String storyContext,
        String address,
        String thumbnailUrl,
        String imageUrl,
        String photoTitle,
        String photoLocation,
        String photoKeywords,
        String storySeed,
        String dataSources,
        Double lat,
        Double lng
) {}
