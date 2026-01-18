package com.jaramgle.backend.dto;

import com.fasterxml.jackson.annotation.JsonAlias;
import com.fasterxml.jackson.annotation.JsonIgnoreProperties;
import java.util.List;

@JsonIgnoreProperties(ignoreUnknown = true)
public record AiQuiz(
        @JsonAlias( {
                "q", "quiz_text" }) String question,
        @JsonAlias({ "options", "choices" }) List<String> options,
        @JsonAlias({ "a", "correct_choice", "answer_index" }) Integer answer){
}
