package com.fairylearn.backend.service.stabilization;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import java.util.List;
import static org.assertj.core.api.Assertions.assertThat;

class PageBuilderTest {

    private PageBuilder pageBuilder;

    @BeforeEach
    void setUp() {
        pageBuilder = new PageBuilder();
    }

    @Test
    @DisplayName("긴 단일 문장(150자 초과)을 soft-break으로 분할한다")
    void testSoftBreakForSingleLongSentence() {
        String longSentence = "옛날 옛적에 아주 아주 길고 끝이 보이지 않는 문장이 있었으니, 이 문장은 너무 길어서 한 페이지에 도저히 담을 수가 없었답니다. 그래서 우리는 이 문장을 의미가 최대한 끊기지 않도록 적절한 위치에서 잘라내어 여러 페이지에 걸쳐 보여주어야만 했습니다.";
        
        List<String> pages = pageBuilder.build(List.of(longSentence), 1);

        assertThat(pages).isNotEmpty();
        pages.forEach(page -> assertThat(page.length()).isLessThanOrEqualTo(150));
        
        String reassembled = String.join(" ", pages);
        assertThat(reassembled).isEqualTo(longSentence);
    }

    @Test
    @DisplayName("여러 문장을 MAX_PAGE_LENGTH에 맞춰 페이지로 구성한다")
    void testBuildWithMultipleSentences() {
        List<String> sentences = List.of(
            "첫 번째 문장입니다. 이 문장은 비교적 짧습니다. 하지만 다음 문장과 합치기에는 충분합니다.",
            "이것은 두 번째 문장이고, 앞의 문장과 합쳐져서 한 페이지를 구성하게 될 것입니다. 길이를 맞추기 위해 내용을 조금 더 추가합니다.",
            "세 번째 문장은 새로운 페이지에서 시작됩니다. 왜냐하면 앞의 문장들과 합치면 150자를 초과하기 때문이죠."
        );

        List<String> pages = pageBuilder.build(sentences, 2);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).contains("첫 번째 문장입니다", "두 번째 문장이고");
        assertThat(pages.get(1)).contains("세 번째 문장은");
        pages.forEach(page -> assertThat(page.length()).isLessThanOrEqualTo(150));
    }
    
    @Test
    @DisplayName("페이지 경계에 걸친 문장들을 올바르게 분할한다")
    void testSentenceExceedingBoundary() {
        List<String> sentences = List.of(
            "이것은 첫 번째 페이지를 가득 채울 아주 긴 문장입니다. 90자를 훌쩍 넘기 때문에 이 문장 하나만으로도 충분히 한 페이지를 만들 수 있습니다.",
            "이 문장은 앞 페이지의 남은 공간에 들어가지 못하고 새로운 페이지를 시작하게 됩니다. 이 문장도 꽤 깁니다."
        );

        List<String> pages = pageBuilder.build(sentences, 2);

        assertThat(pages).hasSize(2);
        assertThat(pages.get(0)).isEqualTo(sentences.get(0));
        assertThat(pages.get(1)).isEqualTo(sentences.get(1));
    }

    @Test
    @DisplayName("총 텍스트가 매우 짧으면 단일 페이지로 생성한다")
    void testBuildWithVeryShortText() {
        List<String> sentences = List.of("아주 짧은 이야기입니다.");
        List<String> pages = pageBuilder.build(sentences, 5);

        assertThat(pages).hasSize(1);
        assertThat(pages.get(0)).isEqualTo("아주 짧은 이야기입니다.");
    }
}