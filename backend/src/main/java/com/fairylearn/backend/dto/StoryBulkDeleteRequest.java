package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import jakarta.validation.constraints.NotEmpty;
import java.util.List;

public record StoryBulkDeleteRequest(
        @JsonAlias({"storyIds", "story_ids"})
        @NotEmpty(message = "삭제할 동화 ID 목록이 필요합니다.")
        List<Long> storyIds
) {
}
