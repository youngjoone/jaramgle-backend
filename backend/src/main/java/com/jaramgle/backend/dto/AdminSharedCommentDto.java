package com.jaramgle.backend.dto;

import com.jaramgle.backend.entity.SharedStoryComment;
import java.time.LocalDateTime;
import lombok.Builder;
import lombok.Value;

@Value
@Builder
public class AdminSharedCommentDto {
    Long id;
    Long parentId;
    Long authorId;
    String authorNickname;
    String content;
    boolean deleted;
    String shareSlug;
    LocalDateTime createdAt;

    public static AdminSharedCommentDto fromEntity(SharedStoryComment comment, String shareSlug) {
        return AdminSharedCommentDto.builder()
                .id(comment.getId())
                .parentId(comment.getParent() != null ? comment.getParent().getId() : null)
                .authorId(comment.getUser() != null ? comment.getUser().getId() : null)
                .authorNickname(comment.getUser() != null ? comment.getUser().getName() : null)
                .content(comment.getContent())
                .deleted(comment.isDeleted())
                .shareSlug(shareSlug)
                .createdAt(comment.getCreatedAt())
                .build();
    }
}
