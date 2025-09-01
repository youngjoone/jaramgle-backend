package com.findme.backend.service;

import com.fasterxml.jackson.databind.ObjectMapper;
import com.findme.backend.dto.*;
import com.findme.backend.entity.Question;
import com.findme.backend.entity.Test;
import com.findme.backend.entity.ResultEntity; // Import ResultEntity
import com.findme.backend.repository.QuestionRepository;
import com.findme.backend.repository.TestRepository;
import com.findme.backend.repository.ResultRepository; // Import ResultRepository
import com.findme.backend.auth.CustomOAuth2User; // Import CustomOAuth2User
import jakarta.annotation.PostConstruct;
import lombok.RequiredArgsConstructor;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.security.core.context.SecurityContextHolder; // Import SecurityContextHolder
import org.springframework.security.core.Authentication; // Import Authentication

import java.io.IOException;
import java.io.InputStream;
import java.time.LocalDateTime;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.stream.Collectors;

@Service
@RequiredArgsConstructor
public class TestService {

    private final TestRepository testRepository;
    private final QuestionRepository questionRepository;
    private final ResultRepository resultRepository;
    private final ObjectMapper objectMapper;

    @PostConstruct
    @Transactional
    public void init() {
        

                if (testRepository.findByCode("mbti_v1").isEmpty()) {
            try {
                InputStream inputStream = new ClassPathResource("seed/tests/mbti_v1.json").getInputStream();
                MbtiTestDto mbtiTestDto = objectMapper.readValue(inputStream, MbtiTestDto.class);

                Test test = new Test("mbti_v1", mbtiTestDto.getTitle(), mbtiTestDto.getVersion(), LocalDateTime.now());
                testRepository.save(test);

                List<Question> questions = mbtiTestDto.getQuestions().stream()
                        .map(q -> new Question(String.valueOf(q.getNo()), test, q.getBody(), false))
                        .collect(Collectors.toList());
                questionRepository.saveAll(questions);

            } catch (IOException e) {
                throw new RuntimeException("Failed to load mbti_v1.json", e);
            }
        }

        if (testRepository.findByCode("teto_egen_v1").isEmpty()) {
            try {
                InputStream inputStream = new ClassPathResource("seed/tests/teto_egen_v1.json").getInputStream();
                MbtiTestDto mbtiTestDto = objectMapper.readValue(inputStream, MbtiTestDto.class);

                Test test = new Test("teto_egen_v1", mbtiTestDto.getTitle(), mbtiTestDto.getVersion(), LocalDateTime.now());
                testRepository.save(test);

                List<Question> questions = mbtiTestDto.getQuestions().stream()
                        .map(q -> new Question(String.valueOf(q.getNo()), test, q.getBody(), false))
                        .collect(Collectors.toList());
                questionRepository.saveAll(questions);

            } catch (IOException e) {
                throw new RuntimeException("Failed to load teto_egen_v1.json", e);
            }
        }
    }

    public Optional<TestResponseDto> getTestByCode(String testCode) {
        return testRepository.findByCode(testCode)
                .map(this::convertToTestResponseDto);
    }

    @Transactional
    public ResultDto calculateResult(String testCode, SubmissionDto submission) {
        Test test = testRepository.findByCode(testCode)
                .orElseThrow(() -> new IllegalArgumentException("Test not found: " + testCode));

        Map<String, Question> questionMap = questionRepository.findByTest(test).stream()
                .collect(Collectors.toMap(Question::getId, q -> q));

        double totalScore = 0;
        for (AnswerDto answer : submission.getAnswers()) {
            Question question = questionMap.get(answer.getQuestionId());
            if (question != null) {
                int value = answer.getValue();
                totalScore += question.isReverse() ? (6 - value) : value;
            }
        }

        double averageScore = totalScore / submission.getAnswers().size();
        
        // Normalize score to 0-100
        // Min possible avg is 1, max is 5. So range is 4.
        double normalizedScore = ((averageScore - 1) / 4.0) * 100;

        // Dummy trait calculation
        Map<String, Double> traits = Map.of(
                "A", normalizedScore * 0.9,
                "B", 100 - (normalizedScore * 0.5),
                "C", (totalScore / (test.getQuestions().size() * 5)) * 100 * 1.2
        );

        // Save result to database
        Long userId = null;
        Authentication authentication = SecurityContextHolder.getContext().getAuthentication();
        if (authentication != null && authentication.getPrincipal() instanceof CustomOAuth2User) {
            CustomOAuth2User principal = (CustomOAuth2User) authentication.getPrincipal();
            userId = principal.getId();
        }

        ResultEntity resultEntity = new ResultEntity(
            null, // ID will be generated
            userId,
            testCode,
            normalizedScore,
            // Convert traits map to JSON string
            traits.entrySet().stream()
                .map(entry -> "\"" + entry.getKey() + "\":" + entry.getValue())
                .collect(Collectors.joining(",", "{", "}")),
            submission.getPoem(), // Save the generated poem from submission
            LocalDateTime.now()
        );
        resultRepository.save(resultEntity);

        return new ResultDto(resultEntity.getId(), normalizedScore, traits);
    }

    private TestResponseDto convertToTestResponseDto(Test test) {
        // Fetch questions eagerly for DTO conversion
        List<QuestionDto> questionDtos = questionRepository.findByTest(test).stream()
                .map(q -> new QuestionDto(q.getId(), q.getBody()))
                .collect(Collectors.toList());
        return new TestResponseDto(test.getCode(), test.getTitle(), questionDtos);
    }

    // Inner DTO classes for parsing JSON
    private static class MbtiTestDto {
        private String code;
        private String title;
        private int version;
        private List<MbtiQuestionDto> questions;

        public String getCode() { return code; }
        public void setCode(String code) { this.code = code; }
        public String getTitle() { return title; }
        public void setTitle(String title) { this.title = title; }
        public int getVersion() { return version; }
        public void setVersion(int version) { this.version = version; }
        public List<MbtiQuestionDto> getQuestions() { return questions; }
        public void setQuestions(List<MbtiQuestionDto> questions) { this.questions = questions; }
    }

    private static class MbtiQuestionDto {
        private int no;
        private String body;

        public int getNo() { return no; }
        public void setNo(int no) { this.no = no; }
        public String getBody() { return body; }
        public void setBody(String body) { this.body = body; }
    }
}
