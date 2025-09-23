package com.fairylearn.backend.controller;

import com.fasterxml.jackson.databind.ObjectMapper;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.WebMvcTest;
import org.springframework.boot.test.mock.mockito.MockBean;
import org.springframework.security.test.context.support.WithMockUser;
import org.springframework.test.web.servlet.MockMvc;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@WebMvcTest(SharedStoryPublicController.class)
class SharedStoryPublicControllerTest {

    @Autowired
    private MockMvc mockMvc;

    @MockBean
    private com.fairylearn.backend.service.StoryShareService storyShareService;

    @MockBean
    private com.fairylearn.backend.service.SharedStoryInteractionService sharedStoryInteractionService;

    private final ObjectMapper objectMapper = new ObjectMapper();

    @Test
    @WithMockUser
    void shouldCreateComment() throws Exception {
        String slug = "test-slug";
        String content = "테스트 댓글";

        mockMvc.perform(post("/api/public/shared-stories/" + slug + "/comments")
                .contentType("application/json")
                .content(objectMapper.writeValueAsString(new com.fairylearn.backend.dto.CreateSharedStoryCommentRequest(content, null))))
                .andExpect(status().isOk());
    }
}
