package com.jaramgle.backend.dto;

public record CommentLikeStatusDto(Long commentId, long likeCount, boolean liked) {}
