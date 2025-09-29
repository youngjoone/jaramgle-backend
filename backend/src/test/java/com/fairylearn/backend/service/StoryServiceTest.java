package com.fairylearn.backend.service;

import com.fairylearn.backend.dto.AiStory;
import com.fairylearn.backend.dto.StableStoryDto;
import com.fairylearn.backend.dto.StoryGenerateRequest;
import com.fasterxml.jackson.databind.ObjectMapper;
import okhttp3.mockwebserver.MockResponse;
import okhttp3.mockwebserver.MockWebServer;
import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.cache.CacheManager;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import java.io.IOException;
import java.util.List;
import java.util.Objects;

import static org.assertj.core.api.Assertions.assertThat;

@SpringBootTest
class StoryServiceTest {

    @Autowired
    private StoryService storyService;

    @Autowired
    private CacheManager cacheManager;
    
    @Autowired
    private ObjectMapper objectMapper;

    public static MockWebServer mockBackEnd;

    @BeforeAll
    static void setUp() throws IOException {
        mockBackEnd = new MockWebServer();
        mockBackEnd.start();
    }

    @AfterAll
    static void tearDown() throws IOException {
        mockBackEnd.shutdown();
    }
    
    @BeforeEach
    void initialize() {
        // Clear cache before each test
        Objects.requireNonNull(cacheManager.getCache("story-generation")).clear();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("ai.python.base-url", () -> mockBackEnd.url("/").toString());
    }

    @Test
    @DisplayName("LLM 호출 실패 시 Failsafe 스토리를 반환해야 한다")
    void testFailsafeOnLlmFailure() {
        // Given: MockWebServer가 에러 응답을 반환하도록 설정
        mockBackEnd.enqueue(new MockResponse().setResponseCode(500));

        StoryGenerateRequest request = new StoryGenerateRequest("용감한 토끼", "8-10", List.of("용기"), List.of("교훈"), 5, "KO", null);

        // When
        StableStoryDto result = storyService.generateStableStoryDto(request);

        // Then
        assertThat(result).isNotNull();
        assertThat(result.title()).isEqualTo("용감한 토끼의 모험");
        assertThat(result.pages().get(0)).contains("용감한 토끼 ‘토토’");
    }

    @Test
    @DisplayName("동일한 요청에 대해 두 번째 호출은 캐시된 결과를 반환해야 한다")
    void testCachingForSameRequest() throws Exception {
        // Given: MockWebServer가 정상 응답을 주도록 설정
        AiStory mockAiStory = new AiStory("Mock Title", objectMapper.createArrayNode(), null);
        mockBackEnd.enqueue(new MockResponse()
                .setBody(objectMapper.writeValueAsString(mockAiStory))
                .addHeader("Content-Type", "application/json"));

        StoryGenerateRequest request = new StoryGenerateRequest("친구들", "5-7", List.of("우정"), List.of("협동"), 4, "KO", null);

        // When: 첫 번째 호출 (Cache Miss)
        storyService.generateStableStoryDto(request);
        
        // Then: 첫 번째 요청이 서버로 갔는지 확인
        assertThat(mockBackEnd.getRequestCount()).isEqualTo(1);

        // When: 두 번째 호출 (Cache Hit)
        storyService.generateStableStoryDto(request);

        // Then: 서버로 가는 요청이 여전히 1개여야 함 (캐시에서 결과를 가져왔기 때문)
        assertThat(mockBackEnd.getRequestCount()).isEqualTo(1);
        
        // 캐시 저장소에 해당 키로 값이 저장되었는지 확인
        assertThat(Objects.requireNonNull(cacheManager.getCache("story-generation")).get(request.toString())).isNotNull();
    }
}