package com.jaramgle.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import com.fasterxml.jackson.annotation.JsonInclude;
import jakarta.validation.constraints.Max;
import jakarta.validation.constraints.Min;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.Size;
import jakarta.validation.constraints.Pattern;
import lombok.AllArgsConstructor;
import lombok.Data;
import lombok.NoArgsConstructor;
import com.fasterxml.jackson.annotation.JsonProperty; 


import java.util.List;
import java.util.stream.Collectors;

@JsonIgnoreProperties(ignoreUnknown = true)
@Data
@NoArgsConstructor
@AllArgsConstructor
public class StoryGenerateRequest {
    private String title;

    @NotBlank
    @Size(max = 50)
    @JsonProperty("age_range")
    @JsonAlias({"ageRange","age_range"})
    private String ageRange;

    @NotEmpty
    private List<String> topics;

    private List<String> objectives;

    @NotNull
    @Min(10)
    @Max(20)
    @JsonProperty("min_pages")
    @JsonAlias({"minPages","min_pages"})
    private Integer minPages;

    @NotBlank
    @Size(max = 10)
    @Pattern(regexp = "^(KO|EN|JA|FR|ES|DE|ZH)$", message = "지원하지 않는 언어 코드입니다.")
    private String language;

    @JsonProperty("character_ids")
    @JsonAlias({"characterIds","character_ids"})
    @Size(max = 2)
    private List<Long> characterIds;

    @Size(max = 150)
    private String moral;

    @JsonProperty("required_elements")
    @JsonAlias({"requiredElements"})
    private List<String> requiredElements;

    @JsonProperty("art_style")
    @JsonAlias({"artStyle"})
    @Size(max = 80)
    private String artStyle;

    @JsonProperty("translation_language")
    @JsonAlias({"translationLanguage", "translation_language"})
    @Size(max = 10)
    @Pattern(regexp = "^(KO|EN|JA|FR|ES|DE|ZH|NONE)?$", message = "지원하지 않는 번역 언어 코드입니다.")
    private String translationLanguage;

    @JsonProperty("generation_profile")
    @JsonAlias({"generationProfile", "generation_profile"})
    @Size(max = 32)
    private String generationProfile;

    @JsonProperty("busan_context")
    @JsonAlias({"busanContext", "busan_context"})
    private BusanContext busanContext;

    public void setRequiredElements(List<String> elements) {
        if (elements == null) {
            this.requiredElements = null;
            return;
        }
        this.requiredElements = elements.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    public void setObjectives(List<String> objectives) {
        if (objectives == null) {
            this.objectives = java.util.Collections.emptyList();
            return;
        }
        this.objectives = objectives.stream()
                .map(item -> item == null ? "" : item.trim())
                .filter(item -> !item.isEmpty())
                .collect(Collectors.toList());
    }

    public void setGenerationProfile(String generationProfile) {
        if (generationProfile == null) {
            this.generationProfile = null;
            return;
        }
        String normalized = generationProfile.trim();
        this.generationProfile = normalized.isEmpty() ? null : normalized;
    }

    @Data
    @NoArgsConstructor
    @AllArgsConstructor
    @JsonIgnoreProperties(ignoreUnknown = true)
    @JsonInclude(JsonInclude.Include.NON_NULL)
    public static class BusanContext {
        @JsonProperty("source_type")
        @JsonAlias({"sourceType"})
        private String sourceType;
        @JsonProperty("source_id")
        @JsonAlias({"sourceId"})
        private String sourceId;
        private String title;
        private String district;
        private String subtitle;
        @JsonProperty("introduction")
        @JsonAlias({"intro"})
        private String introduction;
        @JsonProperty("feature_summary")
        @JsonAlias({"featureSummary", "feature"})
        private String featureSummary;
        @JsonProperty("origin_story")
        @JsonAlias({"originStory", "origin"})
        private String originStory;
        private String description;
        private String address;

        public void setSourceType(String sourceType) { this.sourceType = trimToNull(sourceType); }
        public void setSourceId(String sourceId) { this.sourceId = trimToNull(sourceId); }
        public void setTitle(String title) { this.title = trimToNull(title); }
        public void setDistrict(String district) { this.district = trimToNull(district); }
        public void setSubtitle(String subtitle) { this.subtitle = trimToNull(subtitle); }
        public void setIntroduction(String introduction) { this.introduction = trimToNull(introduction); }
        public void setFeatureSummary(String featureSummary) { this.featureSummary = trimToNull(featureSummary); }
        public void setOriginStory(String originStory) { this.originStory = trimToNull(originStory); }
        public void setDescription(String description) { this.description = trimToNull(description); }
        public void setAddress(String address) { this.address = trimToNull(address); }

        private static String trimToNull(String value) {
            if (value == null) {
                return null;
            }
            String trimmed = value.trim();
            return trimmed.isEmpty() ? null : trimmed;
        }
    }
}
