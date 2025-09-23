package com.fairylearn.backend.dto;

import lombok.Builder;
import lombok.Data;

import java.time.LocalDateTime;
import java.util.ArrayList;
import java.util.List;

@Data
@Builder
public class SharedStoryCommentDto {
    private Long id;
    private Long parentId;
    private String authorNickname;
    private Long authorId;
    private boolean editable;
    private boolean deleted;
    private String content;
    private long likeCount;
    private boolean likedByCurrentUser;
    private LocalDateTime createdAt;
    @Builder.Default
    private List<SharedStoryCommentDto> replies = new ArrayList<>();
}
