package com.fairylearn.backend.dto;

import lombok.AllArgsConstructor;
import lombok.Data;

@Data
@AllArgsConstructor
public class ShareStoryResponse {
    private String shareSlug;
    private String shareUrl;
}
