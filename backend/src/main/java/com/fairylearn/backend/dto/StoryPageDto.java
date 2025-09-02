package com.fairylearn.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fairylearn.backend.entity.StoryPage;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;

@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryPageDto {
    private Long id;
    @JsonAlias({"page", "pageNo"})
    private Integer pageNo;
    private String text;

    public static StoryPageDto fromEntity(StoryPage storyPage) {
        return new StoryPageDto(storyPage.getId(), storyPage.getPageNo(), storyPage.getText());
    }
}