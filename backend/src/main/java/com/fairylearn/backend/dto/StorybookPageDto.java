package com.fairylearn.backend.dto;

import com.fairylearn.backend.entity.StorybookPage;
import lombok.Data;

@Data
public class StorybookPageDto {
    private Long id;
    private Integer pageNumber;
    private String text;
    private String imageUrl;

    public static StorybookPageDto fromEntity(StorybookPage entity) {
        StorybookPageDto dto = new StorybookPageDto();
        dto.setId(entity.getId());
        dto.setPageNumber(entity.getPageNumber());
        dto.setText(entity.getText());
        dto.setImageUrl(entity.getImageUrl());
        return dto;
    }
}
