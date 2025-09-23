package com.fairylearn.backend.dto;

public record CommentLikeStatusDto(Long commentId, long likeCount, boolean liked) {}
