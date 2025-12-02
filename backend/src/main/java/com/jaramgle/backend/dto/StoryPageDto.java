package com.jaramgle.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.jaramgle.backend.entity.StoryPage;
import com.jaramgle.backend.util.AssetUrlResolver;
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
    private String imagePrompt;
    private String imageUrl;
    private String audioUrl;

    public static StoryPageDto fromEntity(StoryPage storyPage) {
        return new StoryPageDto(
                storyPage.getId(),
                storyPage.getPageNo(),
                storyPage.getText(),
                storyPage.getImagePrompt(),
                AssetUrlResolver.toPublicUrl(storyPage.getImageUrl()),
                storyPage.getAudioUrl()
        );
    }
}
