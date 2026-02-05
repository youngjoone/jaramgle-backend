package com.jaramgle.backend.service;

import com.jaramgle.backend.dto.StoryGenerateRequest;
import com.jaramgle.backend.entity.Story;
import com.jaramgle.backend.repository.StoryRepository;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Disabled;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.transaction.annotation.Transactional;

import java.util.List;
import java.util.Optional;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/**
 * 스토리 생성/실패 플로우에 대한 기본 통합 테스트 (스켈레톤)
 * TODO: 외부 AI 호출을 stub/mocking으로 대체하여 안정적인 테스트로 확장 필요.
 */
@SpringBootTest
@ActiveProfiles("local")
@Disabled("LLM/WebClient 호출을 stub/mocking으로 대체하기 전까지는 실행하지 않음")
class StoryFlowIntegrationTest {

    @Autowired
    private StoryService storyService;
    @Autowired
    private HeartWalletService heartWalletService;
    @Autowired
    private StoryRepository storyRepository;

    private Long userId = 1L;

    @BeforeEach
    void setUp() {
        // 테스트를 위한 하트 잔액 확보
        heartWalletService.ensureWallet(userId);
        heartWalletService.adjustHearts(null, userId, 10, "test setup", null);
    }

    @Test
    @Transactional
    void 생성_성공시_하트차감되고_스토리와_퀴즈가_저장된다() {
        int before = heartWalletService.getBalance(userId);

        StoryGenerateRequest req = new StoryGenerateRequest();
        req.setUserId(String.valueOf(userId));
        req.setLanguage("KO");
        req.setTopics(List.of("모험"));
        req.setObjectives(List.of("학습"));
        req.setAgeRange("7-9세");
        req.setMinPages(10);
        req.setMoral(null);
        req.setRequiredElements(List.of());
        req.setCharacterIds(List.of());
        req.setTranslationLanguage(null);

        // 실제 테스트에서는 외부 AI 호출을 mock 해야 함. 현재는 스켈레톤이므로 예외 발생 가능.
        Story story = storyService.generateStory(req);

        int after = heartWalletService.getBalance(userId);
        assertThat(after).isEqualTo(before - 1);

        Optional<Story> saved = storyRepository.findById(story.getId());
        assertThat(saved).isPresent();
        assertThat(saved.get().getQuiz()).isNotBlank();
    }

    @Test
    @Transactional
    void 생성_실패시_하트차감되지_않는다() {
        heartWalletService.ensureWallet(userId);
        heartWalletService.adjustHearts(null, userId, -heartWalletService.getBalance(userId) + 5, "test setup", null);
        int before = heartWalletService.getBalance(userId);

        assertThatThrownBy(() -> {
            // 강제 실패: 잘못된 요청(페이지 최소치 위반)
            StoryGenerateRequest req = new StoryGenerateRequest();
            req.setUserId(String.valueOf(userId));
            req.setLanguage("KO");
            req.setTopics(List.of("모험"));
            req.setObjectives(List.of("학습"));
            req.setAgeRange("7-9세");
            req.setMinPages(0); // 잘못된 값으로 실패 유도
            storyService.generateStory(req);
        }).isInstanceOf(Exception.class);

        int after = heartWalletService.getBalance(userId);
        assertThat(after).isEqualTo(before);
    }
}
