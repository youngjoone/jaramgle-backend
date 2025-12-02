package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.StorybookPage;
import com.jaramgle.backend.util.AssetUrlResolver;
import lombok.Data;

@Data
public class StorybookPageDto {
    private Long id;
    private Integer pageNumber;
    private String text;
    private String imageUrl;
    private String audioUrl;

    public static StorybookPageDto fromEntity(StorybookPage entity) {
        StorybookPageDto dto = new StorybookPageDto();
        dto.setId(entity.getId());
        dto.setPageNumber(entity.getPageNumber());
        dto.setText(entity.getText());
        dto.setImageUrl(AssetUrlResolver.toPublicUrl(entity.getImageUrl()));
        dto.setAudioUrl(entity.getAudioUrl());
        return dto;
    }
}
