package com.findme.backend.config;

import com.findme.backend.dto.TestDefImportRequest;
import com.findme.backend.service.TestDefService;
import com.fasterxml.jackson.databind.ObjectMapper;
import lombok.RequiredArgsConstructor;
import org.springframework.boot.CommandLineRunner;
import org.springframework.core.io.ClassPathResource;
import org.springframework.stereotype.Component;

import java.io.InputStream;

@Component
@RequiredArgsConstructor
public class MbtiDataLoader implements CommandLineRunner {

    private final TestDefService testDefService;
    private final ObjectMapper objectMapper;

    @Override
    public void run(String... args) throws Exception {
        try {
            ClassPathResource resource = new ClassPathResource("seed/tests/mbti_v1.json");
            try (InputStream inputStream = resource.getInputStream()) {
                TestDefImportRequest mbtiTest = objectMapper.readValue(inputStream, TestDefImportRequest.class);
                testDefService.importTestDef(mbtiTest, "system");
                System.out.println("MBTI test loaded: " + mbtiTest.getTitle());
                testDefService.publishTestDef(mbtiTest.getCode(), mbtiTest.getVersion(), "system");
                System.out.println("MBTI test published: " + mbtiTest.getTitle());
            }
        } catch (Exception e) {
            System.err.println("Failed to load MBTI test: " + e.getMessage());
        }
    }
}
